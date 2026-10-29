import http from 'node:http';
import type { AddressInfo } from 'node:net';
import type { ChatChunk } from '../src/lib/api';

/** One `data:` frame carrying an OpenAI-shaped delta. */
export function chunk(content: string): string {
  const c: ChatChunk = {
    id: 'chatcmpl-e2e',
    object: 'chat.completion.chunk',
    created: 1_790_000_000,
    model: 'e2e-model',
    choices: [{ index: 0, delta: { content }, finish_reason: null }],
    usage: null,
  };
  return `data: ${JSON.stringify(c)}\n\n`;
}

/** A complete SSE body: one frame per piece, then [DONE]. */
export function sseBody(pieces: string[]): string {
  return pieces.map(chunk).join('') + 'data: [DONE]\n\n';
}

/**
 * A real event stream on 127.0.0.1 that the test feeds frame by frame.
 * route.fulfill can only send a whole body, so a mid-stream test reroutes
 * the chat request here with route.continue({ url }) instead.
 */
export interface LiveStream {
  url: string;
  /** Resolves when the browser's request arrives and headers are sent. */
  opened: Promise<void>;
  /** Resolves when the browser drops the connection. */
  closed: Promise<void>;
  send(content: string): void;
  stop(): Promise<void>;
}

export async function startLiveStream(): Promise<LiveStream> {
  let open!: () => void;
  let close!: () => void;
  const opened = new Promise<void>((r) => { open = r; });
  const closed = new Promise<void>((r) => { close = r; });
  let res: http.ServerResponse | null = null;

  const server = http.createServer((_req, response) => {
    res = response;
    response.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache',
      'access-control-allow-origin': '*',
    });
    response.flushHeaders();
    response.on('close', close);
    open();
  });
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
  const { port } = server.address() as AddressInfo;

  return {
    url: `http://127.0.0.1:${port}/stream`,
    opened,
    closed,
    send: (content) => { res?.write(chunk(content)); },
    stop: async () => {
      server.closeAllConnections();
      await new Promise<void>((r) => server.close(() => r()));
    },
  };
}
