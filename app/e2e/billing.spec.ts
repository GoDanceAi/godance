import { expect, problem, test } from './mock-api';

// Stands in for the hosted checkout page the API hands back.
const CHECKOUT = 'https://checkout.test/pay/cs_test_e2e';

test.describe('billing top-up', () => {
  test.beforeEach(async ({ page, api }) => {
    await api.signIn();
    await page.route('https://checkout.test/**', (route) =>
      route.fulfill({ contentType: 'text/html', body: '<h1>Test checkout</h1>' }));
  });

  test('a preset amount redirects to the Stripe checkout the API returns', async ({ page, api }) => {
    api.on('POST', '/v1/billing/topup', { json: { sessionId: 'cs_test_e2e', checkoutUrl: CHECKOUT, amountCents: 100_00 } });

    await page.goto('/billing');
    await expect(page.getByText('Current balance: $12.34')).toBeVisible();
    await page.getByRole('button', { name: '$100', exact: true }).click();
    await page.getByRole('button', { name: 'Continue to Stripe' }).click();

    await expect(page).toHaveURL(CHECKOUT);
    await expect(page.getByRole('heading', { name: 'Test checkout' })).toBeVisible();
    expect(api.callsTo('POST', '/v1/billing/topup')[0].body).toEqual({ amountCents: 100_00 });
  });

  test('USDT with a custom amount goes through NOWPayments', async ({ page, api }) => {
    api.on('POST', '/v1/billing/nowpayments/topup', { json: { sessionId: 'np_e2e', checkoutUrl: CHECKOUT, amountCents: 42_00 } });

    await page.goto('/billing');
    await page.getByRole('button', { name: /USDT/ }).click();
    await expect(page.getByRole('button', { name: /USDT/ })).toHaveAttribute('aria-pressed', 'true');
    await page.getByRole('spinbutton').fill('42');
    await page.getByRole('button', { name: 'Continue to NOWPayments' }).click();

    await expect(page).toHaveURL(CHECKOUT);
    expect(api.callsTo('POST', '/v1/billing/nowpayments/topup')[0].body).toEqual({ amountCents: 42_00 });
    expect(api.callsTo('POST', '/v1/billing/topup')).toHaveLength(0);
  });

  test('a checkout the API refuses shows the error and stays on /billing', async ({ page, api }) => {
    api.on('POST', '/v1/billing/topup', problem(503, 'Payments are temporarily unavailable.'));

    await page.goto('/billing');
    await page.getByRole('button', { name: 'Continue to Stripe' }).click();

    await expect(page.getByText('Payments are temporarily unavailable.')).toBeVisible();
    await expect(page).toHaveURL('/billing');
    await expect(page.getByRole('button', { name: 'Continue to Stripe' })).toBeEnabled();
  });

  test('an amount outside $1–$1,000 cannot be checked out', async ({ page, api }) => {
    await page.goto('/billing');
    const amount = page.getByRole('spinbutton');
    const pay = page.getByRole('button', { name: 'Continue to Stripe' });

    await amount.fill('0');
    await expect(pay).toBeDisabled();
    await amount.fill('1001');
    await expect(pay).toBeDisabled();
    await amount.fill('1000');
    await expect(pay).toBeEnabled();
    expect(api.callsTo('POST', '/v1/billing/topup')).toHaveLength(0);
  });
});
