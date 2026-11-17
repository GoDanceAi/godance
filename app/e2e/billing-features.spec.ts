import { expect, makeUser, problem, test } from './mock-api';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

export const CONFIG = {
  enabled: false, threshold_cents: 500, topup_cents: 2500,
  payment_method_on_file: true, last_failure_at: null, last_failure_reason: null,
};

test.beforeEach(async ({ api }) => {
  await api.signIn(makeUser({ email: 'billing@example.test', fullName: 'Test Customer' }));
  api.on('GET', '/v1/billing/auto-recharge', { json: CONFIG });
  api.on('GET', '/v1/usage', { json: [] });
});

test('both amounts must be reviewed and confirmed before auto-recharge PUT', async ({ page, api }) => {
  api.on('PUT', '/v1/billing/auto-recharge', { json: { ...CONFIG, enabled: true, threshold_cents: 0, topup_cents: 1234 } });
  await page.goto('/billing');
  await page.getByLabel('Balance threshold (USD)').fill('0');
  await page.getByLabel('Recharge amount (USD)').fill('12.34');
  await page.getByRole('button', { name: 'Review auto-recharge' }).click();
  const review = page.getByRole('dialog', { name: 'Review auto-recharge' });
  await expect(review).toContainText('$0.00');
  await expect(review).toContainText('$12.34');
  await expect(review).toContainText('off-session');
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(0);
  await review.getByRole('button', { name: 'Cancel', exact: true }).click();
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(0);
  await page.getByRole('button', { name: 'Review auto-recharge' }).click();
  await page.getByRole('button', { name: 'Confirm auto-recharge', exact: true }).click();
  await expect(page.getByText('Auto-recharge is enabled.', { exact: true })).toBeVisible();
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(1);
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')[0].body).toEqual({ enabled: true, threshold_cents: 0, topup_cents: 1234 });
});

test('card saving starts unchecked and is explicit for Stripe only', async ({ page, api }) => {
  api.on('POST', '/v1/billing/topup', problem(502, 'Synthetic checkout unavailable'));
  api.on('POST', '/v1/billing/nowpayments/topup', problem(502, 'Synthetic checkout unavailable'));
  await page.goto('/billing');
  const consent = page.getByRole('checkbox', { name: /Save my card/ });
  await expect(consent).not.toBeChecked();
  expect(api.calls.filter((call) => ['POST', 'PUT'].includes(call.method))).toHaveLength(0);
  await consent.check();
  await page.getByRole('button', { name: 'Continue to Stripe' }).click();
  await expect(page.getByRole('alert')).toContainText('Synthetic checkout unavailable');
  expect(api.callsTo('POST', '/v1/billing/topup')[0].body).toEqual({ amountCents: 2500, save_card: true });
  await page.getByRole('button', { name: /USDT/ }).click();
  await expect(consent).toHaveCount(0);
  await page.getByRole('button', { name: 'Continue to NOWPayments' }).click();
  await expect.poll(() => api.callsTo('POST', '/v1/billing/nowpayments/topup').length).toBe(1);
  expect(api.callsTo('POST', '/v1/billing/nowpayments/topup')[0].body).toEqual({ amountCents: 2500 });
});

test('review preserves the last cent at the safe integer boundary', async ({ page, api }) => {
  api.on('PUT', '/v1/billing/auto-recharge', { json: { ...CONFIG, enabled: true, threshold_cents: Number.MAX_SAFE_INTEGER } });
  await page.goto('/billing');
  await page.getByLabel('Balance threshold (USD)').fill('90071992547409.91');
  await page.getByRole('button', { name: 'Review auto-recharge' }).click();
  await expect(page.getByRole('dialog')).toContainText('$90,071,992,547,409.91');
  await page.getByRole('button', { name: 'Confirm auto-recharge', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')[0].body).toEqual({ enabled: true, threshold_cents: Number.MAX_SAFE_INTEGER, topup_cents: 2500 });
});

const USAGE = [{
  id: 'synthetic-usage-1', recordedAt: '2026-11-16T12:00:00Z', model: 'Synthetic text model', modality: 'TEXT',
  inputTokens: 10, outputTokens: 20, costCents: 123, providerCostCents: 987654321, cacheHit: true, fallbackUsed: false,
}];

test('recent usage excludes internal fields and scopes filtering and totals to loaded rows', async ({ page, api }) => {
  api.on('GET', '/v1/usage', { json: USAGE });
  await page.goto('/billing');
  await expect(page.getByText('Synthetic text model', { exact: true })).toBeVisible();
  await expect(page.getByText('Recent loaded usage total (1 of 1 loaded records):')).toContainText('$1.23');
  expect(await page.locator('body').innerText()).not.toMatch(/providerCostCents|987654321|9,876,543/);
  expect(await page.locator('body').ariaSnapshot()).not.toMatch(/providerCostCents|987654321|9,876,543/);
  expect(api.callsTo('GET', '/v1/usage')[0].query.get('limit')).toBe('200');
  await page.getByLabel('Filter loaded models').fill('no match');
  await expect(page.getByText('Recent loaded usage total (0 of 1 loaded records):')).toContainText('$0.00');
});

test('invalid cents never enter review and missing card cannot enable', async ({ page, api }) => {
  await page.goto('/billing');
  const review = page.getByRole('button', { name: 'Review auto-recharge' });
  for (const value of ['-1', '1.001', '1e3', '9007199254740992', '']) {
    await page.getByLabel('Recharge amount (USD)').fill(value);
    await expect(review).toBeDisabled();
  }
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(0);
  api.on('GET', '/v1/billing/auto-recharge', { json: { ...CONFIG, payment_method_on_file: false } });
  await page.getByRole('button', { name: 'Refresh auto-recharge status' }).click();
  await expect(page.getByText('No saved card is available.', { exact: false })).toBeVisible();
  await page.getByLabel('Recharge amount (USD)').fill('12.34');
  await expect(review).toBeDisabled();
});

test('enabled update needs confirmation; pending double submits produce one PUT; disable is separate', async ({ page, api }) => {
  api.on('GET', '/v1/billing/auto-recharge', { json: { ...CONFIG, enabled: true } });
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  api.on('PUT', '/v1/billing/auto-recharge', async () => {
    await pending;
    return { json: { ...CONFIG, enabled: true, topup_cents: 3000 } };
  });
  await page.goto('/billing');
  await page.getByLabel('Recharge amount (USD)').fill('30.00');
  await page.getByRole('button', { name: 'Review auto-recharge' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('$5.00');
  await expect(dialog).toContainText('$30.00');
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(0);
  await dialog.getByRole('button', { name: 'Confirm auto-recharge' }).click();
  await expect(dialog.getByRole('button', { name: 'Saving…' })).toBeDisabled();
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(1);
  release();
  await expect(dialog).toHaveCount(0);
  api.on('PUT', '/v1/billing/auto-recharge', { json: { ...CONFIG, enabled: false } });
  await page.getByRole('button', { name: 'Disable auto-recharge' }).click();
  await expect(page.getByText('Auto-recharge is disabled.', { exact: true })).toBeVisible();
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')[1].body).toEqual({ enabled: false });
});

test('409 refetches status without replay; 502 preserves editable drafts and confirmed state', async ({ page, api }) => {
  await page.goto('/billing');
  await page.getByLabel('Recharge amount (USD)').fill('12.34');
  api.on('GET', '/v1/billing/auto-recharge', { json: { ...CONFIG, payment_method_on_file: false } });
  api.on('PUT', '/v1/billing/auto-recharge', problem(409, 'Synthetic card is unavailable'));
  await page.getByRole('button', { name: 'Review auto-recharge' }).click();
  await page.getByRole('button', { name: 'Confirm auto-recharge', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Synthetic card is unavailable');
  await expect(page.getByLabel('Recharge amount (USD)')).toHaveValue('12.34');
  expect(api.callsTo('GET', '/v1/billing/auto-recharge')).toHaveLength(2);
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(1);
  api.on('GET', '/v1/billing/auto-recharge', { json: CONFIG });
  await page.getByRole('button', { name: 'Refresh auto-recharge status' }).click();
  await expect(page.getByRole('button', { name: 'Review auto-recharge' })).toBeEnabled();
  api.on('PUT', '/v1/billing/auto-recharge', problem(502, 'Synthetic provider unavailable'));
  await page.getByRole('button', { name: 'Review auto-recharge' }).click();
  await page.getByRole('button', { name: 'Confirm auto-recharge', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Synthetic provider unavailable');
  await expect(page.getByText('Auto-recharge is disabled.', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Recharge amount (USD)')).toHaveValue('12.34');
  expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(2);
});

test('failed GETs retry reads only and success URL cannot confirm payment', async ({ page, api }) => {
  api.on('GET', '/v1/billing/auto-recharge', problem(403, 'Synthetic access denied'));
  api.on('GET', '/v1/usage', problem(502, 'Synthetic usage unavailable'));
  await page.goto('/billing?success=true&amountCents=999999');
  await expect(page.getByText('Current balance: $12.34')).toBeVisible();
  await expect(page.getByText('Synthetic access denied', { exact: true })).toBeVisible();
  await expect(page.getByText(/Synthetic usage unavailable/)).toBeVisible();
  api.on('GET', '/v1/billing/auto-recharge', { json: CONFIG });
  api.on('GET', '/v1/usage', { json: [] });
  await page.getByRole('button', { name: 'Refresh auto-recharge status' }).click();
  await page.getByRole('button', { name: 'Refresh recent usage' }).click();
  await expect(page.getByText('No recent usage records.')).toBeVisible();
  expect(api.calls.filter((call) => ['POST', 'PUT'].includes(call.method))).toHaveLength(0);
});

test('expired billing session returns to sign-in', async ({ page, api }) => {
  api.rejectSession('/v1/billing/auto-recharge');
  await page.goto('/billing');
  await expect(page).toHaveURL(/\/login/);
});

for (const theme of ['light', 'dark']) {
  test(`synthetic ${theme} usage and review evidence with keyboard focus`, async ({ page, api }, info) => {
    await page.addInitScript((value) => localStorage.setItem('gd-theme', value), theme);
    api.on('GET', '/v1/usage', { json: USAGE });
    await page.goto('/billing');
    await expect(page.getByText('Synthetic text model', { exact: true })).toBeVisible();
    const folder = path.resolve('../docs/round-15-evidence/02');
    await mkdir(folder, { recursive: true });
    await page.locator('section', { has: page.getByRole('heading', { name: 'Recent loaded usage' }) }).screenshot({ path: path.join(folder, `${info.project.name}-${theme}-usage.png`) });
    const button = page.getByRole('button', { name: 'Review auto-recharge' });
    await button.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cancel', exact: true })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(page.getByRole('button', { name: 'Confirm auto-recharge', exact: true })).toBeFocused();
    await page.getByRole('dialog').screenshot({ path: path.join(folder, `${info.project.name}-${theme}-review.png`) });
    await page.keyboard.press('Escape');
    await expect(button).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(api.callsTo('PUT', '/v1/billing/auto-recharge')).toHaveLength(0);
  });
}
