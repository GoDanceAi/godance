/**
 * Run `work` over `items` with at most `limit` in flight. `work` may return
 * false to stop: nothing new is started after that, and what is in flight
 * finishes. Resolves once everything started has finished.
 */
export async function pool(items, limit, work) {
  let next = 0;
  let stopped = false;
  const worker = async () => {
    while (!stopped && next < items.length) {
      const item = items[next++];
      if ((await work(item)) === false) stopped = true;
    }
  };
  await Promise.all(Array.from({ length: Math.max(1, Math.min(limit, items.length)) }, worker));
  return { stopped, started: next };
}
