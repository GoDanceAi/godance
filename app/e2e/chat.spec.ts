import type { Page } from '@playwright/test';
import type { ModelListResponse } from '../src/lib/api';
import { expect, problem, test, type MockApi } from './mock-api';
import { sseBody, startLiveStream } from './sse';

const MODELS: ModelListResponse = {
  object: 'list',
  data: [
    { id: 'gpt-4o-mini', object: 'model', owned_by: 'openai',
      godance: { modality: 'TEXT', requires_async: false, pricing: { input_microcents_per_1k: 15, output_microcents_per_1k: 60 } } },
    { id: 'acme-chat-1', object: 'model', owned_by: 'acme',
      godance: { modality: 'TEXT', requires_async: false, pricing: { input_microcents_per_1k: 100, output_microcents_per_1k: 300 } } },
    { id: 'acme-image-1', object: 'model', owned_by: 'acme',
      godance: { modality: 'IMAGE', requires_async: false, pricing: { per_image_microcents: 4000 } } },
    { id: 'godance-auto', object: 'model', owned_by: 'godance',
      godance: { virtual: true, modality: 'TEXT' } },
  ],
};

async function openChat(page: Page, api: MockApi) {
  await api.signIn();
  api.on('GET', '/v1/models', { json: MODELS });
  await page.goto('/chat');
}

async function ask(page: Page, prompt: string) {
  await page.getByPlaceholder('Ask anything').fill(prompt);
  await page.getByRole('button', { name: 'Send' }).click();
}

test.describe('chat', () => {
  test('streams the reply from the model picked in the live catalog', async ({ page, api }) => {
    await openChat(page, api);
    api.on('POST', '/v1/chat/completions', {
      contentType: 'text/event-stream',
      body: sseBody(['Hello', ', ', 'world.']),
    });

    await page.getByRole('button', { name: /Advanced/ }).click();
    const picker = page.getByLabel('Pick an exact model');
    // Only text models from /v1/models: no image model, no virtual routing id.
    await picker.selectOption({ label: 'acme-chat-1 (acme)' });
    await expect(picker.locator('option', { hasText: 'acme-image-1' })).toHaveCount(0);
    await expect(picker.locator('option', { hasText: 'godance-auto' })).toHaveCount(0);
    await ask(page, 'Say hello');

    await expect(page.getByText('Hello, world.')).toBeVisible();
    await expect(page.getByText('Say hello')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();
    const [call] = api.callsTo('POST', '/v1/chat/completions');
    expect(call.body).toEqual({
      model: 'acme-chat-1',
      messages: [{ role: 'user', content: 'Say hello' }],
      stream: true,
    });
    expect(call.headers.accept).toBe('text/event-stream');
  });

  test('an upstream 502 shows an error and drops the empty reply', async ({ page, api }) => {
    await openChat(page, api);
    // A gateway error page, not problem+json.
    api.on('POST', '/v1/chat/completions', { status: 502, contentType: 'text/html', body: '<h1>502 Bad Gateway</h1>' });

    await ask(page, 'Are you there?');

    await expect(page.getByText('HTTP 502')).toBeVisible();
    await expect(page.getByText('Are you there?')).toBeVisible();
    await expect(page.getByText('assistant', { exact: true })).toHaveCount(0);
    await expect(page.getByPlaceholder('Ask anything')).toBeEnabled();
  });

  // Bug: streamChat drops the problem+json body on errors, so the user sees "HTTP 402" instead of the API's reason.
  test.fixme('a 402 shows the reason the API gives', async ({ page, api }) => {
    await openChat(page, api);
    api.on('POST', '/v1/chat/completions', problem(402, 'insufficient credit; top up via /v1/billing/topup'));

    await ask(page, 'Hello?');

    await expect(page.getByText('insufficient credit; top up via /v1/billing/topup')).toBeVisible();
  });

  test('Stop mid-stream keeps the partial reply and closes the connection', async ({ page, api }) => {
    const live = await startLiveStream();
    try {
      await openChat(page, api);
      api.on('POST', '/v1/chat/completions', (_call, route) => route.continue({ url: live.url }));

      await ask(page, 'Tell me a long story');
      await live.opened;
      live.send('Once upon');
      await expect(page.getByText('Once upon')).toBeVisible();
      await expect(page.getByPlaceholder('Ask anything')).toBeDisabled();

      await page.getByRole('button', { name: 'Stop' }).click();

      await live.closed;
      await expect(page.getByRole('button', { name: 'Send' })).toBeVisible();
      await expect(page.getByText('Once upon')).toBeVisible();
      await expect(page.getByText('Chat failed')).toHaveCount(0);
      await expect(page.getByPlaceholder('Ask anything')).toBeEnabled();
    } finally {
      await live.stop();
    }
  });

  // Bug: ChatPage never aborts its stream on unmount, so leaving mid-reply keeps the paid request running.
  test.fixme('leaving the page mid-stream closes the connection', async ({ page, api }) => {
    const live = await startLiveStream();
    try {
      await openChat(page, api);
      api.on('POST', '/v1/chat/completions', (_call, route) => route.continue({ url: live.url }));

      await ask(page, 'Tell me a long story');
      await live.opened;
      live.send('Once upon');
      await expect(page.getByText('Once upon')).toBeVisible();

      await page.getByRole('link', { name: 'Godance', exact: true }).click();
      await expect(page).toHaveURL('/dashboard');

      await live.closed;
    } finally {
      await live.stop();
    }
  });
});
