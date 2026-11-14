import { expect, test } from '@playwright/test';
import { compareIds, compareLink, diffFrom, durationText, toggleSelected } from '../src/lib/compare';
import { makeJob } from './generation';

// Round 10 brief 03: which generations a compare link names, and what in a
// prompt differs from the first one's. These tests run the module directly;
// no page is opened. The page itself is covered in recipes.spec.ts.

test.describe('comparing', () => {
  const A = '7a0b0000-0000-4000-8000-000000000001';
  const B = '7a0b0000-0000-4000-8000-000000000002';

  test('the link names the jobs in order, and reads back', () => {
    expect(compareLink([A, B])).toBe(`/compare?jobs=${A},${B}`);
    expect(compareIds(`${A},${B}`)).toEqual([A, B]);
  });

  test('anything that is not a job id is ignored, a repeat counts once, and four is the most', () => {
    const ids = Array.from({ length: 6 }, (_, i) => `7a0b0000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`);
    expect(compareIds(`nope,${A}, ${A.toUpperCase()} ,../x,${B}`)).toEqual([A, B]);
    expect(compareIds(ids.join(','))).toEqual(ids.slice(0, 4));
    expect(compareIds(null)).toEqual([]);
  });

  test('a selection takes up to four, and gives one back', () => {
    const four = ['a', 'b', 'c', 'd'];
    expect(toggleSelected(['a'], 'b')).toEqual(['a', 'b']);
    expect(toggleSelected(four, 'e')).toEqual(four);
    expect(toggleSelected(four, 'b')).toEqual(['a', 'c', 'd']);
  });

  const changed = (base: string, text: string) => diffFrom(base, text)!.filter((p) => p.changed).map((p) => p.text);

  test('a changed word is marked, and the pieces join back into the prompt', () => {
    const text = 'A blue mug on a kitchen shelf';
    const parts = diffFrom('A red mug on a kitchen shelf', text)!;
    expect(parts.map((p) => p.text).join('')).toBe(text);
    expect(changed('A red mug on a kitchen shelf', text)).toEqual(['blue']);
  });

  test('added words are marked together with the space between them', () => {
    expect(changed('A mug', 'A bright blue mug')).toEqual(['bright blue ']);
  });

  test('the same prompt has nothing marked, and words only the first has are not shown', () => {
    expect(changed('A red mug', 'A red mug')).toEqual([]);
    expect(changed('A big red mug', 'A red mug')).toEqual([]);
  });

  test('Chinese is compared a character at a time', () => {
    expect(changed('一盏暖红色的纸灯笼', '一盏翠绿色的纸灯笼')).toEqual(['翠绿']);
  });

  test('two very long prompts are not compared', () => {
    const long = Array.from({ length: 2100 }, (_, i) => `w${i}`).join(' ');
    expect(diffFrom(long, `${long} more`)).toBeNull();
  });

  test('how long a job took', () => {
    expect(durationText(makeJob())).toBe('18 s');
    expect(durationText(makeJob({ startedAt: '2026-11-13T12:00:00Z', completedAt: '2026-11-13T12:02:05Z' }))).toBe('2 min 5 s');
    expect(durationText(makeJob({ startedAt: null, completedAt: '2026-11-13T12:03:00Z' }))).toBe('3 min');
    expect(durationText(makeJob({ completedAt: null }))).toBeNull();
  });
});
