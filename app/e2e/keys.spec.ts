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
          createdAt: '2026-10-28T12:00:00Z', lastUsedAt: null, expiresAt: null,
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
