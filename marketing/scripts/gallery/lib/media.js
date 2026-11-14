/**
 * Turning a downloaded output into the files a recipe refers to.
 *
 * Images become WebP, with a 480-pixel thumbnail beside them. A video is
 * kept as it came; its size and length are read from the file itself, and a
 * poster is made when ffmpeg is installed.
 *
 * Files are named <slug>/<model>-<n>.<ext>, the layout the recipe format
 * shows, under the work folder's media folder. Media is never committed.
 */
import { spawn } from 'node:child_process';
import { mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Read a big-endian box header at `offset`: its type, where its body starts and where it ends. */
function boxAt(bytes, offset, limit) {
  if (offset + 8 > limit) return null;
  let size = bytes.readUInt32BE(offset);
  const type = bytes.toString('latin1', offset + 4, offset + 8);
  let body = offset + 8;
  if (size === 1) {
    if (offset + 16 > limit) return null;
    size = Number(bytes.readBigUInt64BE(offset + 8));
    body = offset + 16;
  } else if (size === 0) {
    size = limit - offset;
  }
  if (size < body - offset || offset + size > limit) return null;
  return { type, body, end: offset + size };
}

function* boxes(bytes, start, end) {
  for (let offset = start; offset < end; ) {
    const box = boxAt(bytes, offset, end);
    if (!box) return;
    yield box;
    offset = box.end;
  }
}

/**
 * The frame size and length of an MP4, read from its own headers, so that
 * no extra tool is needed. Returns null when the file is not an MP4 this
 * can read.
 */
export function probeMp4(bytes) {
  for (const top of boxes(bytes, 0, bytes.length)) {
    if (top.type !== 'moov') continue;
    let duration = null;
    let size = null;
    for (const box of boxes(bytes, top.body, top.end)) {
      if (box.type === 'mvhd') {
        const version = bytes.readUInt8(box.body);
        const timescale = bytes.readUInt32BE(box.body + (version === 1 ? 20 : 12));
        const units = version === 1 ? Number(bytes.readBigUInt64BE(box.body + 24)) : bytes.readUInt32BE(box.body + 16);
        if (timescale > 0) duration = units / timescale;
      }
      if (box.type === 'trak' && !size) {
        for (const inner of boxes(bytes, box.body, box.end)) {
          if (inner.type !== 'tkhd') continue;
          // Width and height are the last eight bytes, as 16.16 fixed point. A sound track has none.
          const width = bytes.readUInt32BE(inner.end - 8) / 65536;
          const height = bytes.readUInt32BE(inner.end - 4) / 65536;
          if (width >= 1 && height >= 1) size = { width: Math.round(width), height: Math.round(height) };
        }
      }
    }
    if (size && duration && duration > 0) return { ...size, duration_seconds: Math.round(duration * 100) / 100 };
    return null;
  }
  return null;
}

function run(command, args) {
  return new Promise((resolve) => {
    const child = spawn(command, args, { stdio: 'ignore' });
    child.on('error', () => resolve(false));
    child.on('close', (code) => resolve(code === 0));
  });
}

/** Whether ffmpeg is installed. */
export const hasFfmpeg = () => run('ffmpeg', ['-version']);

/**
 * Save an image output as WebP with its thumbnail.
 * @returns the output as the recipe records it, with paths relative to `mediaDir`.
 */
export async function saveImage({ bytes, mediaDir, slug, name }) {
  const { default: sharp } = await import('sharp');
  await mkdir(path.join(mediaDir, slug), { recursive: true });
  const file = `${slug}/${name}.webp`;
  const thumb = `${slug}/${name}-480.webp`;
  const upright = sharp(bytes).rotate();
  const info = await upright.clone().webp({ quality: 90 }).toFile(path.join(mediaDir, file));
  await upright.clone().resize({ width: 480, withoutEnlargement: true }).webp({ quality: 80 }).toFile(path.join(mediaDir, thumb));
  return { file, thumb, content_type: 'image/webp', width: info.width, height: info.height };
}

/**
 * Save a video output, and a poster beside it when `ffmpeg` is true.
 * @returns the output as the recipe records it, with paths relative to `mediaDir`.
 */
export async function saveVideo({ bytes, contentType, mediaDir, slug, name, ffmpeg = false }) {
  const probe = probeMp4(bytes);
  if (!probe) throw new Error(`the video is not an MP4 whose size and length can be read (${contentType || 'no content type'})`);
  await mkdir(path.join(mediaDir, slug), { recursive: true });
  const file = `${slug}/${name}.mp4`;
  await writeFile(path.join(mediaDir, file), bytes);
  const output = { file, content_type: 'video/mp4', duration_seconds: probe.duration_seconds, width: probe.width, height: probe.height };

  if (ffmpeg) {
    const { default: sharp } = await import('sharp');
    const frame = path.join(mediaDir, slug, `${name}-poster.png`);
    const poster = `${slug}/${name}-poster.webp`;
    const at = String(Math.min(0.5, probe.duration_seconds / 2));
    if (await run('ffmpeg', ['-y', '-loglevel', 'error', '-ss', at, '-i', path.join(mediaDir, file), '-frames:v', '1', frame])) {
      await sharp(frame).webp({ quality: 85 }).toFile(path.join(mediaDir, poster));
      output.poster = poster;
    }
    await rm(frame, { force: true });
  }
  // Keys in the order the recipe format lists them.
  const { poster, ...rest } = output;
  return poster ? { file: rest.file, poster, content_type: rest.content_type, duration_seconds: rest.duration_seconds, width: rest.width, height: rest.height } : rest;
}
