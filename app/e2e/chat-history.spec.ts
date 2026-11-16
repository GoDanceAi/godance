import type { Page, TestInfo } from '@playwright/test';
import type { AppendMessage } from '../src/lib/chat-history';
import { expect, makeUser, problem, sessionCleared, sessionStarted, test } from './mock-api';
import { chunk, sseBody, startLiveStream } from './sse';
import { CONVERSATION, savedChat, savedMessage, serveChatHistory } from './chat-history';

test('a rejected save retains the answer and retry save never resends generation', async ({ page, api }) => {
  await api.signIn(makeUser({ email: 'adult@example.test' }));
  api.on('GET', '/v1/models', { json: { object: 'list', data: [] } });
  api.on('POST', '/v1/chat/completions', { contentType: 'text/event-stream', body: sseBody(['Synthetic completed answer.']) });
  const saved = serveChatHistory(api);
  api.on('POST', `/v1/conversations/${CONVERSATION}/messages`, problem(400, 'Rejected message'));
  await page.goto('/chat');
  await page.getByPlaceholder('Ask anything').fill('Synthetic question');
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.getByText('Synthetic completed answer.', { exact: true })).toBeVisible();
  expect(api.callsTo('POST', '/v1/conversations')).toHaveLength(0);
  await page.getByRole('button', { name: 'Save this chat', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('could not be saved');
  await expect(page.getByText('Synthetic completed answer.', { exact: true })).toBeVisible();
  api.on('POST', `/v1/conversations/${CONVERSATION}/messages`, (call) => {
    const sent = call.body as AppendMessage;
    const message = { ...savedMessage(sent.content, sent.role, saved.messages.length + 1), model: sent.model ?? null };
    saved.messages.push(message);
    return { status: 201, json: message };
  });
  await page.getByRole('button', { name: 'Retry save', exact: true }).click();
  await expect.poll(() => saved.messages.length).toBe(2);
  expect(api.callsTo('POST', '/v1/chat/completions')).toHaveLength(1);
  expect(api.callsTo('POST', '/v1/conversations')).toHaveLength(1);
});

for (const ageStatus of ['CONSENT_GRANTED', 'UNKNOWN', 'CONSENT_PENDING'] as const) {
  test(`${ageStatus} has no saved-history controls or calls`, async ({ page, api }) => {
    await api.signIn(makeUser({ ageStatus: ageStatus as ReturnType<typeof makeUser>['ageStatus'] }));
    api.on('GET', '/v1/models', { json: { object: 'list', data: [] } });
    await page.goto('/chat');
    if (ageStatus !== 'CONSENT_PENDING') {
      api.on('POST', '/v1/chat/completions', { contentType: 'text/event-stream', body: sseBody(['Ephemeral synthetic answer.']) });
      await page.getByPlaceholder('Ask anything').fill('Synthetic child question');
      await page.getByRole('button', { name: 'Send', exact: true }).click();
      await expect(page.getByText('Ephemeral synthetic answer.', { exact: true })).toBeVisible();
    }
    await expect(page.getByRole('button', { name: 'Saved chats', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Save this chat', exact: true })).toHaveCount(0);
    expect(api.calls.filter((call) => call.path.startsWith('/v1/conversations'))).toHaveLength(0);
  });
}

async function setup(page: Page, api: Parameters<typeof serveChatHistory>[0]) {
  await api.signIn(makeUser({ email: 'adult@example.test' }));
  api.on('GET', '/v1/models', { json: { object: 'list', data: [] } });
  api.on('POST', '/v1/chat/completions', { contentType: 'text/event-stream', body: sseBody(['Synthetic completed answer.']) });
  await page.goto('/chat');
}
async function ask(page: Page, text = 'Synthetic question') {
  await page.getByPlaceholder('Ask anything').fill(text);
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeVisible();
}
async function capture(page: Page, info: TestInfo, state: string) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  if (process.env.R15_CAPTURE_EVIDENCE !== '1') return;
  for (const theme of ['light', 'dark']) {
    const switcher = page.getByRole('button', { name: `Switch to ${theme} mode` });
    if (await switcher.count()) await switcher.click();
    await page.screenshot({ path: info.outputPath(`${state}-${info.project.name}-${theme}.png`), fullPage: true });
  }
}

test('explicit save serializes each message once; continuing saves only new turns; reload remains ephemeral', async ({ page, api }, info) => {
  const history = serveChatHistory(api);
  await setup(page, api);
  await ask(page);
  await capture(page, info, 'ephemeral');
  expect(api.calls.filter((call) => call.path.startsWith('/v1/conversations'))).toHaveLength(0);
  await page.getByRole('button', { name: 'Save this chat', exact: true }).click();
  await expect.poll(() => history.messages.length).toBe(2);
  await expect(page.getByText(/Saving is on/)).toBeVisible();
  await capture(page, info, 'saved');
  await ask(page, 'Second synthetic question');
  await expect.poll(() => history.messages.length).toBe(4);
  expect(api.callsTo('POST', '/v1/conversations')).toHaveLength(1);
  expect(api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`)).toHaveLength(4);
  for (const call of api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`)) {
    expect(call.body).not.toHaveProperty('cost_cents');
    expect(call.body).not.toHaveProperty('input_tokens');
    expect(call.body).not.toHaveProperty('output_tokens');
  }
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  expect(stored).not.toContain('Synthetic completed answer.');
  expect(stored).not.toContain('Synthetic question');
  const before = api.calls.filter((call) => call.path.startsWith('/v1/conversations')).length;
  await page.reload();
  await expect(page.getByText(/Ephemeral chat/)).toBeVisible();
  expect(api.calls.filter((call) => call.path.startsWith('/v1/conversations'))).toHaveLength(before);
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await page.getByRole('button', { name: 'Synthetic question', exact: true }).click();
  await expect(page.getByText('Second synthetic question', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'New chat', exact: true }).click();
  await expect(page.getByText(/Ephemeral chat/)).toBeVisible();
  await expect(page.getByText('Synthetic completed answer.', { exact: true })).toHaveCount(0);
});

test('lost append response is reconciled with known IDs without replay or generation', async ({ page, api }) => {
  const history = serveChatHistory(api);
  api.on('POST', `/v1/conversations/${CONVERSATION}/messages`, async (call, route) => {
    const sent = call.body as AppendMessage;
    const saved = { ...savedMessage(sent.content, sent.role, history.messages.length + 1), model: sent.model ?? null };
    history.messages.push(saved);
    if (sent.role === 'assistant') { await route.abort('failed'); return; }
    return { status: 201, json: saved };
  });
  await setup(page, api);
  await ask(page);
  await page.getByRole('button', { name: 'Save this chat', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('matching message was found');
  await expect(page.getByText('Synthetic completed answer.', { exact: true })).toBeVisible();
  expect(api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`)).toHaveLength(2);
  expect(api.callsTo('GET', `/v1/conversations/${CONVERSATION}/messages`)).toHaveLength(1);
  await page.getByRole('button', { name: 'Continue saving' }).click();
  expect(api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`)).toHaveLength(2);
  expect(api.callsTo('POST', '/v1/chat/completions')).toHaveLength(1);
});

test('unresolved append pauses saving and explicit checks never replay a POST', async ({ page, api }, info) => {
  const history = serveChatHistory(api);
  api.on('POST', `/v1/conversations/${CONVERSATION}/messages`, async (call, route) => {
    const sent = call.body as AppendMessage;
    if (sent.role === 'assistant') { await route.abort('failed'); return; }
    const saved = savedMessage(sent.content, 'user'); history.messages.push(saved);
    return { status: 201, json: saved };
  });
  await setup(page, api);
  await ask(page);
  await page.getByRole('button', { name: 'Save this chat', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('unresolved');
  await expect(page.getByText('Synthetic completed answer.', { exact: true })).toBeVisible();
  await capture(page, info, 'save-failed');
  await page.getByRole('button', { name: 'Check saved copy' }).click();
  await expect.poll(() => api.callsTo('GET', `/v1/conversations/${CONVERSATION}/messages`).length).toBe(2);
  await ask(page, 'Question while saving is paused');
  expect(api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`)).toHaveLength(2);
  expect(api.callsTo('POST', '/v1/chat/completions')).toHaveLength(2);
});

test('an unknown create outcome stays unresolved and checks never create again', async ({ page, api }) => {
  const history = serveChatHistory(api);
  api.on('POST', '/v1/conversations', async (_call, route) => { history.seed(); await route.abort('failed'); });
  await setup(page, api);
  await ask(page);
  await page.getByRole('button', { name: 'Save this chat', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('create response was not confirmed');
  await expect(page.getByRole('button', { name: 'Save this chat', exact: true })).toBeDisabled();
  await expect(page.getByText('Synthetic completed answer.', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  expect(api.callsTo('POST', '/v1/conversations')).toHaveLength(1);
  expect(api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`)).toHaveLength(0);
});

test('duplicate synchronous send and save events cause one generation and serialized appends', async ({ page, api }) => {
  const history = serveChatHistory(api);
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  api.on('POST', `/v1/conversations/${CONVERSATION}/messages`, async (call) => {
    if (!history.messages.length) await gate;
    const sent = call.body as AppendMessage;
    const saved = { ...savedMessage(sent.content, sent.role, history.messages.length + 1), model: sent.model ?? null };
    history.messages.push(saved); return { status: 201, json: saved };
  });
  await setup(page, api);
  await page.getByPlaceholder('Ask anything').fill('Synthetic question');
  await page.locator('main form').evaluate((form) => {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  });
  await expect(page.getByText('Synthetic completed answer.', { exact: true })).toBeVisible();
  expect(api.callsTo('POST', '/v1/chat/completions')).toHaveLength(1);
  await page.getByRole('button', { name: 'Save this chat', exact: true }).evaluate((button) => {
    button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    button.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  });
  await expect.poll(() => api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`).length).toBe(1);
  expect(api.callsTo('POST', '/v1/conversations')).toHaveLength(1);
  await expect(page.getByRole('button', { name: 'New chat', exact: true })).toBeDisabled();
  release();
  await expect.poll(() => history.messages.length).toBe(2);
});

test('streaming disables switching and archive; Stop before a chunk never stores or resends a blank assistant', async ({ page, api }) => {
  const history = serveChatHistory(api); history.seed();
  const live = await startLiveStream();
  try {
    await setup(page, api);
    await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
    await page.getByRole('button', { name: 'Synthetic saved chat', exact: true }).click();
    api.on('POST', '/v1/chat/completions', (_call, route) => route.continue({ url: live.url }));
    await page.getByPlaceholder('Ask anything').fill('First question');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await live.opened;
    for (const name of ['New chat', 'Saved chats', 'Archive chat', 'Synthetic saved chat']) {
      await expect(page.getByRole('button', { name, exact: true })).toBeDisabled();
    }
    await page.getByRole('button', { name: 'Stop', exact: true }).click();
    await live.closed;
    await expect(page.getByText('Partial or interrupted reply · Not saved')).toBeVisible();
    await expect.poll(() => history.messages.length).toBe(1);
    expect(history.messages[0].role).toBe('user');
    api.on('POST', '/v1/chat/completions', { contentType: 'text/event-stream', body: sseBody(['Next completed answer.']) });
    await ask(page, 'Second question');
    const sent = api.callsTo('POST', '/v1/chat/completions')[1].body as { messages: { content: string }[] };
    expect(sent.messages.every((message) => message.content.trim() !== '')).toBe(true);
    await expect.poll(() => history.messages.length).toBe(3);
    expect(history.messages.filter((message) => message.role === 'assistant')).toHaveLength(1);
  } finally { await live.stop(); }
});

test('a quietly truncated SSE reply stays visible but explicit save omits partial assistant text', async ({ page, api }) => {
  const history = serveChatHistory(api);
  await setup(page, api);
  api.on('POST', '/v1/chat/completions', { contentType: 'text/event-stream', body: chunk('Synthetic truncated reply') });
  await ask(page);
  await expect(page.getByText('Synthetic truncated reply', { exact: true })).toBeVisible();
  await expect(page.getByText('Partial or interrupted reply · Not saved')).toBeVisible();
  await page.getByRole('button', { name: 'Save this chat', exact: true }).click();
  await expect.poll(() => history.messages.length).toBe(1);
  expect(history.messages[0].role).toBe('user');
});

test('loaded message order and authoritative metadata survive a continued saved chat', async ({ page, api }) => {
  const original = [savedMessage('Synthetic system instruction', 'system', 1), savedMessage('Loaded user question', 'user', 2),
    { ...savedMessage('Loaded assistant answer', 'assistant', 3), input_tokens: 12, output_tokens: 5, cost_cents: 7 }];
  const history = serveChatHistory(api, original); history.seed();
  await setup(page, api);
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await page.getByRole('button', { name: 'Synthetic saved chat', exact: true }).click();
  const view = page.locator('[aria-label="Chat messages"]');
  await expect(view).toContainText('system');
  await expect(view).toContainText('12 input tokens');
  await expect(view).toContainText('5 output tokens');
  await expect(view).toContainText('$0.07');
  await ask(page, 'Continue loaded chat');
  await expect.poll(() => history.messages.length).toBe(5);
  expect(history.messages.slice(0, 3)).toEqual(original);
  const sent = api.callsTo('POST', '/v1/chat/completions')[0].body as { messages: { role: string; content: string }[] };
  expect(sent.messages.slice(0, 3)).toEqual(original.map(({ role, content }) => ({ role, content })));
});

test('archiving confirms retention, removes the active row and makes the selected chat read-only', async ({ page, api }) => {
  const history = serveChatHistory(api, [savedMessage('Saved synthetic message')]); history.seed();
  await setup(page, api);
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await page.getByRole('button', { name: 'Synthetic saved chat', exact: true }).click();
  page.once('dialog', async (dialog) => { expect(dialog.message()).toContain('30 days'); expect(dialog.message()).toContain('not immediate erasure'); await dialog.accept(); });
  await page.getByRole('button', { name: 'Archive chat', exact: true }).click();
  await expect(page.getByText(/Archived chat · Read-only/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Synthetic saved chat', exact: true })).toHaveCount(0);
  await expect(page.getByPlaceholder('Ask anything')).toBeDisabled();
  expect(api.callsTo('POST', '/v1/chat/completions')).toHaveLength(0);
});

test('an externally archived selection is detected before generation; foreign and failed reads preserve the view', async ({ page, api }) => {
  const history = serveChatHistory(api, [savedMessage('Saved synthetic message')]); history.seed();
  await setup(page, api);
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await page.getByRole('button', { name: 'Synthetic saved chat', exact: true }).click();
  history.archive();
  await page.getByPlaceholder('Ask anything').fill('Must not generate');
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.getByPlaceholder('Ask anything')).toBeDisabled();
  expect(api.callsTo('POST', '/v1/chat/completions')).toHaveLength(0);
  await page.getByRole('button', { name: 'New chat', exact: true }).click();
  api.on('GET', '/v1/conversations', { json: [savedChat()] });
  api.on('GET', `/v1/conversations/${CONVERSATION}`, problem(404, 'conversation not found'));
  api.on('GET', `/v1/conversations/${CONVERSATION}/messages`, problem(404, 'conversation not found'));
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await page.getByRole('button', { name: 'Synthetic saved chat', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('could not be found for your account');
  await expect(page.getByText(/Ephemeral chat/)).toBeVisible();
});

test('logout fences a live stream and another account never receives its transcript', async ({ page, api }) => {
  const live = await startLiveStream();
  try {
    await setup(page, api);
    api.on('POST', '/v1/auth/logout', sessionCleared());
    api.on('POST', '/v1/chat/completions', (_call, route) => route.continue({ url: live.url }));
    await page.getByPlaceholder('Ask anything').fill('First account question');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await live.opened; live.send('First account partial');
    await expect(page.getByText('First account partial', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Log out', exact: true }).click();
    await live.closed;
    api.mockUser(makeUser({ id: '00000000-0000-4000-8000-000000000002', email: 'next@example.test' }));
    api.on('POST', '/v1/auth/login', sessionStarted('unusable-next-session'));
    await page.getByLabel('Email').fill('next@example.test');
    await page.getByLabel('Password').fill('unusable-test-password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.goto('/chat');
    await expect(page.getByText('First account partial', { exact: true })).toHaveCount(0);
    await expect(page.getByText('First account question', { exact: true })).toHaveCount(0);
    expect(api.calls.filter((call) => call.path.startsWith('/v1/conversations'))).toHaveLength(0);
  } finally { await live.stop(); }
});

test('a same-account age-status change clears adult saved state before another turn', async ({ page, api }) => {
  const history = serveChatHistory(api, [savedMessage('Adult saved transcript')]); history.seed();
  await setup(page, api);
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await page.getByRole('button', { name: 'Synthetic saved chat', exact: true }).click();
  api.mockUser(makeUser({ ageStatus: 'CONSENT_GRANTED' }));
  await ask(page, 'Synthetic adult turn before rehydration');
  await expect(page.getByRole('button', { name: 'Saved chats', exact: true })).toHaveCount(0);
  await expect(page.getByText('Adult saved transcript', { exact: true })).toHaveCount(0);
  await expect(page.getByPlaceholder('Ask anything')).toBeEnabled();
});

test('a late pre-archive list cannot re-add the archived chat and failed reads can be retried', async ({ page, api }) => {
  const history = serveChatHistory(api, [savedMessage('Saved synthetic message')]); history.seed();
  await setup(page, api);
  api.on('GET', '/v1/conversations', problem(503, 'Unavailable'));
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Could not load saved chats');
  api.on('GET', '/v1/conversations', { json: [savedChat()] });
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await page.getByRole('button', { name: 'Synthetic saved chat', exact: true }).click();
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  api.on('GET', '/v1/conversations', async () => { await gate; return { json: [savedChat()] }; });
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await expect(page.getByText('Loading saved chats…')).toBeVisible();
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Archive chat', exact: true }).click();
  await expect(page.getByText(/Archived chat · Read-only/)).toBeVisible();
  release();
  await expect(page.getByRole('button', { name: 'Synthetic saved chat', exact: true })).toHaveCount(0);
});

test('an ambiguous archive never claims success and a status check detects the saved archive', async ({ page, api }) => {
  const history = serveChatHistory(api, [savedMessage('Saved synthetic message')]); history.seed();
  await setup(page, api);
  await page.getByRole('button', { name: 'Saved chats', exact: true }).click();
  await page.getByRole('button', { name: 'Synthetic saved chat', exact: true }).click();
  api.on('DELETE', `/v1/conversations/${CONVERSATION}`, async (_call, route) => { history.archive(); await route.abort('failed'); });
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Archive chat', exact: true }).click();
  await expect(page.getByText(/Archival is unconfirmed/)).toBeVisible();
  await expect(page.getByPlaceholder('Ask anything')).toBeDisabled();
  await expect(page.getByText(/Archived chat · Read-only/)).toHaveCount(0);
  await page.getByRole('button', { name: 'Check chat status' }).click();
  await expect(page.getByText(/Archived chat · Read-only/)).toBeVisible();
  expect(api.callsTo('DELETE', `/v1/conversations/${CONVERSATION}`)).toHaveLength(1);
});

test('late save callbacks after logout cannot attach content to another account', async ({ page, api }) => {
  serveChatHistory(api);
  await setup(page, api);
  api.on('POST', '/v1/auth/logout', sessionCleared());
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  api.on('POST', `/v1/conversations/${CONVERSATION}/messages`, async (_call, route) => {
    await gate;
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(savedMessage('Synthetic question')) }).catch(() => undefined);
  });
  await ask(page);
  await page.getByRole('button', { name: 'Save this chat', exact: true }).click();
  await expect.poll(() => api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`).length).toBe(1);
  await page.getByRole('button', { name: 'Log out', exact: true }).click();
  api.mockUser(makeUser({ id: '00000000-0000-4000-8000-000000000002', email: 'next@example.test' }));
  api.on('POST', '/v1/auth/login', sessionStarted('unusable-next-session'));
  await page.getByLabel('Email').fill('next@example.test');
  await page.getByLabel('Password').fill('unusable-test-password');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  release();
  await page.goto('/chat');
  await expect(page.getByText(/Ephemeral chat/)).toBeVisible();
  await expect(page.getByText('Synthetic completed answer.', { exact: true })).toHaveCount(0);
  expect(api.callsTo('POST', `/v1/conversations/${CONVERSATION}/messages`)).toHaveLength(1);
});
