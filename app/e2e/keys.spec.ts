import type { ApiKeySummary } from '../src/lib/api';
import { expect, problem, test } from './mock-api';

// Not a real key: the format only has to look like what the API returns.
const PLAINTEXT = 'gd_live_e2e0fake0plaintext0shown0once';

test.describe('API keys', () => {
  test('create shows the plaintext once, lists the key, and revoke removes it', async ({ page, api }) => {
    await api.signIn();
    let keys: ApiKeySummary[] = [];
    api.on('GET', '/v1/keys', () => ({ json: keys }))
      .on('POST', '/v1/keys', (call) => {
        const { name } = call.body as { name: string };
        keys = [{
          id: 'key-1', name, prefix: 'e2e0fake', scopes: null, active: true,
          createdAt: '2026-10-28T12:00:00Z', lastUsedAt: null, expiresAt: null, image_response: 'async',
        }];
        return { status: 201, json: { id: 'key-1', name, prefix: 'e2e0fake', plaintext: PLAINTEXT, createdAt: keys[0].createdAt } };
      })
      .on('DELETE', '/v1/keys/key-1', () => {
        keys = [];
        return { status: 204 };
      });

    await page.goto('/keys');
    await expect(page.getByText('No keys yet.')).toBeVisible();

    await page.getByPlaceholder('Key name').fill('  CI deploy  ');
    await page.getByRole('button', { name: 'Create key' }).click();

    await expect(page.getByText('Key created: CI deploy')).toBeVisible();
    await expect(page.getByText(PLAINTEXT)).toBeVisible();
    expect(api.callsTo('POST', '/v1/keys')[0].body).toEqual({ name: 'CI deploy' });
    const row = page.getByRole('row', { name: /CI deploy/ });
    await expect(row).toContainText('gd_live_e2e0fake…');
    // The form named no image response, so the key shows what the server gave it.
    await expect(row.getByLabel('Image responses for CI deploy')).toHaveValue('async');
    await expect(page.getByPlaceholder('Key name')).toHaveValue('');

    // Shown once: dismissed, it is gone from the page.
    await page.getByRole('button', { name: "I've saved it — dismiss" }).click();
    await expect(page.getByText(PLAINTEXT)).toHaveCount(0);

    // Declining the confirm sends nothing...
    page.once('dialog', (d) => void d.dismiss());
    await row.getByRole('button', { name: 'Revoke' }).click();
    await expect(row).toBeVisible();

    // ...accepting it revokes the key.
    page.once('dialog', (d) => void d.accept());
    await row.getByRole('button', { name: 'Revoke' }).click();
    await expect(page.getByText('No keys yet.')).toBeVisible();
    await expect(row).toHaveCount(0);
    expect(api.callsTo('DELETE', /^\/v1\/keys\//).map((c) => c.path)).toEqual(['/v1/keys/key-1']);
  });

  test('a failed create shows the error and keeps the list', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/keys', { json: [] })
      .on('POST', '/v1/keys', problem(429, 'Key limit reached for this account.'));

    await page.goto('/keys');
    await page.getByPlaceholder('Key name').fill('one too many');
    await page.getByRole('button', { name: 'Create key' }).click();

    await expect(page.getByText('Key limit reached for this account.')).toBeVisible();
    await expect(page.getByText('Key created')).toHaveCount(0);
    await expect(page.getByText('No keys yet.')).toBeVisible();
  });
});

/** One key as GET /v1/keys lists it. */
function key(overrides: Partial<ApiKeySummary> = {}): ApiKeySummary {
  return {
    id: 'key-1', name: 'production backend', prefix: 'e2e0fake', scopes: null, active: true,
    createdAt: '2026-10-28T12:00:00Z', lastUsedAt: null, expiresAt: null, image_response: 'async',
    ...overrides,
  };
}

test.describe("a key's image responses", () => {
  const HELP = "Synchronous: image requests wait for the result, as OpenAI's API does. "
    + 'Asynchronous: they return a job to poll.';

  test('each key shows its setting, and changing it saves it', async ({ page, api }) => {
    await api.signIn();
    const keys = [key(), key({ id: 'key-2', name: 'notebook', prefix: 'e2e0sync', image_response: 'sync' })];
    api.on('GET', '/v1/keys', () => ({ json: keys }))
      .on('PATCH', '/v1/keys/key-1', (call) => {
        keys[0] = { ...keys[0], ...(call.body as Pick<ApiKeySummary, 'image_response'>) };
        return { json: keys[0] };
      });

    await page.goto('/keys');
    await expect(page.getByText(HELP)).toBeVisible();
    const production = page.getByLabel('Image responses for production backend');
    await expect(production).toHaveValue('async');
    await expect(production.locator('option')).toHaveText(['Asynchronous', 'Synchronous']);
    await expect(page.getByLabel('Image responses for notebook')).toHaveValue('sync');

    await production.selectOption('sync');

    await expect.poll(() => api.callsTo('PATCH', '/v1/keys/key-1').length).toBe(1);
    expect(api.callsTo('PATCH', '/v1/keys/key-1')[0].body).toEqual({ image_response: 'sync' });
    await expect(production).toHaveValue('sync');
    await expect(production).toBeEnabled();
    // The other key is untouched, and it survives a reload.
    await expect(page.getByLabel('Image responses for notebook')).toHaveValue('sync');
    await page.reload();
    await expect(page.getByLabel('Image responses for production backend')).toHaveValue('sync');
    // The wider table scrolls inside its card: the page does not move sideways, phone included.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test('a change the API refuses says so and leaves the key as it was', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/keys', { json: [key()] })
      .on('PATCH', '/v1/keys/key-1', problem(404, 'api key not found'));

    await page.goto('/keys');
    const setting = page.getByLabel('Image responses for production backend');
    await setting.selectOption('sync');

    await expect(page.getByRole('alert')).toHaveText('api key not found');
    await expect(setting).toHaveValue('async');
    await expect(setting).toBeEnabled();
  });

  test('a new key can ask for either, or leave it to the default', async ({ page, api }) => {
    await api.signIn();
    let keys: ApiKeySummary[] = [];
    api.on('GET', '/v1/keys', () => ({ json: keys }))
      .on('POST', '/v1/keys', (call) => {
        const body = call.body as { name: string; image_response?: 'async' | 'sync' };
        const created = key({ id: `key-${keys.length + 1}`, name: body.name, image_response: body.image_response ?? 'async' });
        keys = [...keys, created];
        return { status: 201, json: { ...created, plaintext: PLAINTEXT } };
      });

    await page.goto('/keys');
    const choice = page.getByLabel('Image responses for the new key');
    await expect(choice).toHaveValue('');
    await expect(choice.locator('option')).toHaveText(
      ['Image responses: default', 'Image responses: synchronous', 'Image responses: asynchronous']);

    await page.getByPlaceholder('Key name').fill('sdk script');
    await choice.selectOption('sync');
    await page.getByRole('button', { name: 'Create key' }).click();

    await expect(page.getByLabel('Image responses for sdk script')).toHaveValue('sync');
    expect(api.callsTo('POST', '/v1/keys')[0].body).toEqual({ name: 'sdk script', image_response: 'sync' });
    // The form goes back to the default for the next key.
    await expect(choice).toHaveValue('');

    await page.getByPlaceholder('Key name').fill('poller');
    await choice.selectOption('async');
    await page.getByRole('button', { name: 'Create key' }).click();
    await expect(page.getByLabel('Image responses for poller')).toHaveValue('async');
    expect(api.callsTo('POST', '/v1/keys')[1].body).toEqual({ name: 'poller', image_response: 'async' });
  });

  test('a key from an API without the setting shows no control', async ({ page, api }) => {
    await api.signIn();
    const { image_response: _none, ...older } = key();
    api.on('GET', '/v1/keys', { json: [older] });

    await page.goto('/keys');

    await expect(page.getByRole('row', { name: /production backend/ })).toBeVisible();
    await expect(page.getByLabel('Image responses for production backend')).toHaveCount(0);
  });
});
