import type { Page } from '@playwright/test';
import type { ApiKeySummary } from '../src/lib/api';
import { API_ORIGIN } from './api-origin';
import { expect, FAKE_KEY, makeKey, problem, serveKeys, test, type MockApi } from './mock-api';

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

// Round 12 brief 03: a key's owner caps what it may spend and says when it stops working.

const row = (page: Page, name: string | RegExp) => page.getByRole('row', { name });
const limits = (page: Page) => page.getByRole('dialog');

/** The keys page over `keys`, served to the round 12 contract. */
async function openKeys(page: Page, api: MockApi, keys: ApiKeySummary[], path = '/keys') {
  await api.signIn();
  serveKeys(api, keys);
  await page.goto(path);
  await expect(page.getByRole('heading', { level: 1, name: 'API keys' })).toBeVisible();
}

test.describe('a capped key', () => {
  test('is created with a daily cap and an expiry, and shown once', async ({ page, api }) => {
    const keys: ApiKeySummary[] = [];
    await openKeys(page, api, keys);

    await page.getByLabel('Key name').fill('nightly script');
    await page.getByLabel('Daily cap (USD)').fill('2.50');
    await page.getByLabel(/^Expires/).fill('2031-06-30');
    await page.getByRole('button', { name: 'Create key' }).click();

    const shown = page.getByRole('status', { name: 'New API key' });
    await expect(shown).toContainText('Key created: nightly script');
    await expect(shown).toContainText(FAKE_KEY);
    await expect(shown).toContainText('Set it as GODANCE_API_KEY');
    await expect(shown).toContainText('It can spend up to $2.50 a day.');
    await expect(shown).toContainText('It stops working after Jun 30, 2031.');
    const body = api.callsTo('POST', '/v1/keys')[0].body as { name: string; daily_cap_cents: number; expires_at: string };
    expect(body).toEqual({ name: 'nightly script', daily_cap_cents: 250, expires_at: body.expires_at });
    expect(Date.parse(body.expires_at)).toBeGreaterThan(Date.parse('2031-06-30T00:00:00Z'));

    // The list shows its cap with nothing spent, and its expiry.
    await expect(row(page, /nightly script/)).toContainText('Today $0.00 of $2.50');
    await expect(row(page, /nightly script/)).toContainText('This month $0.00 · no cap');
    await expect(row(page, /nightly script/)).toContainText('Jun 30, 2031');
    // The form is empty again for the next key.
    await expect(page.getByLabel('Key name')).toHaveValue('');
    await expect(page.getByLabel('Daily cap (USD)')).toHaveValue('');

    // Shown once: dismissed, the key is nowhere on the page, and a reload does not bring it back.
    await shown.getByRole('button', { name: "I've saved it — dismiss" }).click();
    await expect(page.getByText(FAKE_KEY)).toHaveCount(0);
    await page.reload();
    await expect(row(page, /nightly script/)).toBeVisible();
    await expect(page.getByText(FAKE_KEY)).toHaveCount(0);
    await expect(page.getByRole('status', { name: 'New API key' })).toHaveCount(0);
  });

  test('a cap that is not an amount is refused before any request', async ({ page, api }) => {
    await openKeys(page, api, []);

    await page.getByLabel('Key name').fill('typo');
    await page.getByLabel('Daily cap (USD)').fill('2,50');
    await page.getByRole('button', { name: 'Create key' }).click();

    await expect(page.getByRole('alert')).toHaveText('Enter an amount in dollars, such as 5.00, or leave it empty for no cap.');
    expect(api.callsTo('POST', '/v1/keys')).toHaveLength(0);

    // Corrected, it goes through.
    await page.getByLabel('Daily cap (USD)').fill('$2.5');
    await page.getByRole('button', { name: 'Create key' }).click();
    await expect(page.getByRole('status', { name: 'New API key' })).toContainText('It can spend up to $2.50 a day.');
    expect(api.callsTo('POST', '/v1/keys')[0].body).toEqual({ name: 'typo', daily_cap_cents: 250 });
  });

  test('"Create a key for this": the page opens with the recipe\'s name and a 5.00 daily cap', async ({ page, api }) => {
    const keys: ApiKeySummary[] = [];
    await openKeys(page, api, keys, `/keys?for=${encodeURIComponent('Mug on a kitchen shelf')}`);

    await expect(page.getByText('A key for “Mug on a kitchen shelf”')).toBeVisible();
    await expect(page.getByLabel('Key name')).toHaveValue('Mug on a kitchen shelf');
    await expect(page.getByLabel('Daily cap (USD)')).toHaveValue('5.00');

    // Here the key can also be set to answer image requests synchronously.
    await page.getByLabel('Image responses for the new key').selectOption('sync');
    await page.getByRole('button', { name: 'Create a key for this' }).click();

    const shown = page.getByRole('status', { name: 'New API key' });
    await expect(shown).toContainText('Key created: Mug on a kitchen shelf');
    await expect(shown).toContainText('Set it as GODANCE_API_KEY');
    expect(api.callsTo('POST', '/v1/keys')[0].body).toEqual(
      { name: 'Mug on a kitchen shelf', image_response: 'sync', daily_cap_cents: 500 });
    await expect(page.getByLabel('Image responses for Mug on a kitchen shelf')).toHaveValue('sync');
    await expect(row(page, /Mug on a kitchen shelf/)).toContainText('Today $0.00 of $5.00');
  });
});

test.describe("a key's caps and spend", () => {
  /** Three keys: one well inside its caps, one that has reached its daily cap, one that expired. */
  const three = () => [
    makeKey({
      name: 'mug recipe', daily_cap_cents: 500, spent_today_cents: 120, monthly_cap_cents: 5000, spent_month_cents: 4310,
      expires_at: '2031-03-31T12:00:00Z',
    }),
    makeKey({
      id: 'key-2', name: 'notebook', prefix: 'e2e0sync', image_response: 'sync',
      daily_cap_cents: 200, spent_today_cents: 200, spent_month_cents: 940,
    }),
    makeKey({ id: 'key-3', name: 'old script', prefix: 'e2e0gone', expires_at: '2026-01-15T12:00:00Z' }),
  ];

  test('each key shows what it spent today and this month against its caps, and when it expires', async ({ page, api }) => {
    await openKeys(page, api, three());

    const mug = row(page, /mug recipe/);
    await expect(mug).toContainText('Today $1.20 of $5.00');
    await expect(mug).toContainText('This month $43.10 of $50.00');
    await expect(mug).toContainText('Mar 31, 2031');
    await expect(mug.getByText('Expired')).toHaveCount(0);

    // At its daily cap: it says so. No monthly cap: it says that too.
    const notebook = row(page, /notebook/);
    await expect(notebook).toContainText('Today $2.00 of $2.00 · cap reached');
    await expect(notebook).toContainText('This month $9.40 · no cap');
    await expect(notebook).toContainText('Never');
    // Round 10's setting is still there for each key.
    await expect(page.getByLabel('Image responses for notebook')).toHaveValue('sync');
    await expect(page.getByLabel('Image responses for mug recipe')).toHaveValue('async');

    const old = row(page, /old script/);
    await expect(old).toContainText('Today $0.00 · no cap');
    await expect(old.getByText('Expired')).toBeVisible();
    await expect(old).toContainText('Jan 15, 2026');

    // The wider table scrolls inside its card: the page does not move sideways, phone included.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  test('the limits are changed in a dialog that sends only what changed', async ({ page, api }) => {
    const keys = three();
    await openKeys(page, api, keys);

    await page.getByRole('button', { name: 'Edit limits for mug recipe' }).click();
    await expect(limits(page).getByRole('heading', { name: 'Limits for mug recipe' })).toBeVisible();
    // It opens with the key's limits as they are, and says what it has spent.
    await expect(limits(page)).toContainText('it has spent $1.20 today and $43.10 this month');
    await expect(limits(page).getByLabel('Daily cap (USD)')).toHaveValue('5.00');
    await expect(limits(page).getByLabel('Monthly cap (USD)')).toHaveValue('50.00');
    await expect(limits(page).getByLabel('Expires')).not.toHaveValue('');

    // Only the daily cap changes.
    await limits(page).getByLabel('Daily cap (USD)').fill('7.5');
    await limits(page).getByRole('button', { name: 'Save limits' }).click();

    await expect(limits(page)).toHaveCount(0);
    expect(api.callsTo('PATCH', '/v1/keys/key-1').map((call) => call.body)).toEqual([{ daily_cap_cents: 750 }]);
    await expect(row(page, /mug recipe/)).toContainText('Today $1.20 of $7.50');
    // The rest is as it was, and a reload shows the same.
    await expect(row(page, /mug recipe/)).toContainText('This month $43.10 of $50.00');
    expect(keys[0].expires_at).toBe('2031-03-31T12:00:00Z');
    await page.reload();
    await expect(row(page, /mug recipe/)).toContainText('Today $1.20 of $7.50');
  });

  test('an emptied cap and an emptied expiry are cleared, and a new expiry is the end of its day', async ({ page, api }) => {
    const keys = three();
    await openKeys(page, api, keys);

    await page.getByRole('button', { name: 'Edit limits for mug recipe' }).click();
    await limits(page).getByLabel('Monthly cap (USD)').fill('');
    await limits(page).getByLabel('Expires').fill('');
    await limits(page).getByRole('button', { name: 'Save limits' }).click();

    await expect(limits(page)).toHaveCount(0);
    expect(api.callsTo('PATCH', '/v1/keys/key-1')[0].body).toEqual({ monthly_cap_cents: null, expires_at: null });
    await expect(row(page, /mug recipe/)).toContainText('This month $43.10 · no cap');
    await expect(row(page, /mug recipe/)).toContainText('Never');

    // A key without limits gets both caps and an expiry.
    await page.getByRole('button', { name: 'Edit limits for old script' }).click();
    await limits(page).getByLabel('Daily cap (USD)').fill('1');
    await limits(page).getByLabel('Monthly cap (USD)').fill('20.00');
    await limits(page).getByLabel('Expires').fill('2032-02-29');
    await limits(page).getByRole('button', { name: 'Save limits' }).click();

    await expect(limits(page)).toHaveCount(0);
    const sent = api.callsTo('PATCH', '/v1/keys/key-3')[0].body as { daily_cap_cents: number; monthly_cap_cents: number; expires_at: string };
    expect(sent).toEqual({ daily_cap_cents: 100, monthly_cap_cents: 2000, expires_at: sent.expires_at });
    expect(await page.evaluate((iso) => {
      const at = new Date(iso);
      return [at.getFullYear(), at.getMonth() + 1, at.getDate(), at.getHours(), at.getMinutes(), at.getSeconds()];
    }, sent.expires_at)).toEqual([2032, 2, 29, 23, 59, 59]);
    const old = row(page, /old script/);
    await expect(old).toContainText('Today $0.00 of $1.00');
    await expect(old).toContainText('Feb 29, 2032');
    // It works again: no longer shown as expired.
    await expect(old.getByText('Expired')).toHaveCount(0);
  });

  test('nothing changed sends nothing, and Cancel and Escape close the dialog', async ({ page, api }) => {
    await openKeys(page, api, three());

    await page.getByRole('button', { name: 'Edit limits for notebook' }).click();
    await limits(page).getByRole('button', { name: 'Save limits' }).click();
    await expect(limits(page)).toHaveCount(0);

    await page.getByRole('button', { name: 'Edit limits for notebook' }).click();
    await limits(page).getByLabel('Daily cap (USD)').fill('9');
    await limits(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(limits(page)).toHaveCount(0);

    await page.getByRole('button', { name: 'Edit limits for notebook' }).click();
    // What was typed and cancelled is not kept: the dialog opens with the key's own cap.
    await expect(limits(page).getByLabel('Daily cap (USD)')).toHaveValue('2.00');
    await page.keyboard.press('Escape');
    await expect(limits(page)).toHaveCount(0);

    expect(api.callsTo('PATCH', /^\/v1\/keys\//)).toHaveLength(0);
    await expect(row(page, /notebook/)).toContainText('Today $2.00 of $2.00');
  });

  test('a cap that is not an amount, or a day already gone, keeps the dialog open and sends nothing', async ({ page, api }) => {
    await openKeys(page, api, three());

    await page.getByRole('button', { name: 'Edit limits for mug recipe' }).click();
    await limits(page).getByLabel('Monthly cap (USD)').fill('lots');
    await limits(page).getByRole('button', { name: 'Save limits' }).click();
    await expect(limits(page).getByRole('alert')).toHaveText('Enter an amount in dollars, such as 5.00, or leave it empty for no cap.');

    await limits(page).getByLabel('Monthly cap (USD)').fill('60');
    await limits(page).getByLabel('Expires').fill('2021-05-05');
    await limits(page).getByRole('button', { name: 'Save limits' }).click();
    await expect(limits(page).getByRole('alert')).toHaveText('Pick a day from today on, or leave it empty for a key that does not expire.');

    expect(api.callsTo('PATCH', /^\/v1\/keys\//)).toHaveLength(0);
    await expect(limits(page)).toBeVisible();
  });

  test('a change the API refuses says why in the dialog, and the key stays as it was', async ({ page, api }) => {
    await openKeys(page, api, three());
    api.on('PATCH', '/v1/keys/key-1', problem(400, 'expires_at must be in the future'));

    await page.getByRole('button', { name: 'Edit limits for mug recipe' }).click();
    await limits(page).getByLabel('Daily cap (USD)').fill('8');
    await limits(page).getByRole('button', { name: 'Save limits' }).click();

    await expect(limits(page).getByRole('alert')).toHaveText('expires_at must be in the future');
    await expect(limits(page).getByRole('button', { name: 'Save limits' })).toBeEnabled();
    await page.keyboard.press('Escape');
    await expect(row(page, /mug recipe/)).toContainText('Today $1.20 of $5.00');
  });

  test('an expired key keeps its expiry when only a cap is changed', async ({ page, api }) => {
    const keys = three();
    await openKeys(page, api, keys);

    await page.getByRole('button', { name: 'Edit limits for old script' }).click();
    await limits(page).getByLabel('Daily cap (USD)').fill('3');
    await limits(page).getByRole('button', { name: 'Save limits' }).click();

    await expect(limits(page)).toHaveCount(0);
    // The day in the past was left alone, so it is not sent, and not refused.
    expect(api.callsTo('PATCH', '/v1/keys/key-3')[0].body).toEqual({ daily_cap_cents: 300 });
    await expect(row(page, /old script/).getByText('Expired')).toBeVisible();
  });

  test('a key from an API without caps shows no spend and nothing to edit', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/keys', { json: [key()] });

    await page.goto('/keys');

    const older = row(page, /production backend/);
    await expect(older).toBeVisible();
    await expect(older).not.toContainText('Today');
    await expect(page.getByRole('button', { name: /Edit limits/ })).toHaveCount(0);
    // What it always showed is still there.
    await expect(page.getByLabel('Image responses for production backend')).toHaveValue('async');
    await expect(older.getByRole('button', { name: 'Revoke' })).toBeVisible();
  });
});

test.describe('using a key with MCP', () => {
  test('"Use with MCP" opens the setup for Claude Code, Cursor and any client, with the key only as $GODANCE_API_KEY', async ({ page, api }) => {
    await api.signIn();
    api.on('GET', '/v1/keys', { json: [makeKey({ name: 'my agent' })] });

    await page.goto('/keys');
    await page.getByRole('row', { name: /my agent/ }).getByRole('button', { name: 'Use my agent with MCP' }).click();

    const dialog = page.getByRole('dialog', { name: 'Use “my agent” with MCP' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('figure', { name: 'Claude Code' })).toContainText(
      `claude mcp add --transport http godance ${API_ORIGIN}/mcp --header "Authorization: Bearer $GODANCE_API_KEY"`);
    const cursor = dialog.getByRole('figure', { name: 'Cursor' });
    await expect(cursor).toContainText(`"url": "${API_ORIGIN}/mcp"`);
    await expect(cursor).toContainText('"Authorization": "Bearer ${env:GODANCE_API_KEY}"');
    const generic = dialog.getByRole('figure', { name: 'Another MCP client' });
    await expect(generic).toContainText(`URL: ${API_ORIGIN}/mcp`);
    await expect(generic).toContainText('Transport: Streamable HTTP');
    await expect(generic).toContainText('Header: Authorization: Bearer $GODANCE_API_KEY');
    // No snippet holds a key: the page never has one to put there.
    for (const figure of await dialog.getByRole('figure').all()) {
      await expect(figure).not.toContainText('gd_live_');
      await expect(figure).not.toContainText(FAKE_KEY);
    }
    await expect(dialog.getByRole('link', { name: /Use from an AI assistant/ }))
      .toHaveAttribute('href', 'https://godance.ai/docs/#mcp');

    // Nothing was asked of the API to show it, and Escape closes it.
    expect(api.callsTo('POST', /^\/v1\//)).toHaveLength(0);
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
  });
});
