import type { Page } from '@playwright/test';
import type { AdminCatalogModel, AdminProposal, AdminUserView, UserRole } from '../src/lib/api';
import { expect, makeUser, problem, test, type MockApi } from './mock-api';

// What the console offers each role. The API enforces all of this; here the
// point is that nobody is shown a control the server would refuse.

const CUSTOMER: AdminUserView = {
  id: '00000000-0000-4000-8000-000000000002',
  email: 'grace@example.com',
  fullName: 'Grace Hopper',
  role: 'USER',
  active: true,
  creditBalanceCents: 5_00,
  dailyCreditCapCents: null,
  monthlyCreditCapCents: null,
  apiKeyCount: 0,
  createdAt: '2026-10-01T00:00:00Z',
};

const PROPOSAL: AdminProposal = {
  id: 7,
  type: 'PRICE_CHANGE',
  status: 'PENDING',
  modelName: 'gpt-4o',
  provider: 'OPENAI',
  modality: 'TEXT',
  payload: { field: 'input_1k', old_value: 100, new_value: 200 },
  source: 'openrouter',
  sourceUrl: null,
  detectedAt: '2026-10-29T00:00:00Z',
  reviewedBy: null,
  reviewedAt: null,
};

const MODEL = {
  id: 3,
  modelName: 'gpt-4o',
  provider: 'OPENAI',
  modality: 'TEXT',
  status: 'ACTIVE',
  enabled: true,
  inputMicrocentsPer1k: 100,
  outputMicrocentsPer1k: 200,
  perImageMicrocents: 0,
  perSecondMicrocents: 0,
  markupOverride: null,
  effectiveMarkupPercent: 20,
} as AdminCatalogModel;

async function signInAs(api: MockApi, role: UserRole): Promise<void> {
  await api.signIn(makeUser({ role }));
  api.on('GET', '/v1/admin/me', { json: { email: 'ada@example.com', role } })
    .on('GET', '/v1/admin/users', () => ({ json: { users: [CUSTOMER] } }))
    .on('GET', /^\/v1\/admin\/users\/[^/]+\/ledger$/, {
      json: { entries: [], page: 0, totalPages: 0, totalEntries: 0 },
    })
    .on('GET', '/v1/admin/pricing/proposals', { json: { proposals: [PROPOSAL] } })
    .on('GET', '/v1/admin/pricing/models', { json: { models: [MODEL] } });
}

async function openCustomer(page: Page, email: string): Promise<void> {
  await page.goto('/admin/users');
  await page.getByPlaceholder('email or user UUID…').fill(email);
  await page.getByRole('button', { name: 'Search' }).click();
  await page.getByRole('cell', { name: email }).click();
  await expect(page.getByRole('dialog').getByText('Ledger', { exact: true })).toBeVisible();
}

test.describe('admin console by role', () => {
  test('SUPPORT reads everything and is offered no action', async ({ page, api }) => {
    await signInAs(api, 'SUPPORT');

    await openCustomer(page, CUSTOMER.email);
    await expect(page.getByText('support console')).toBeVisible();
    await expect(page.getByText('Adjust credit')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Save role' })).toHaveCount(0);

    await page.goto('/admin/proposals');
    await expect(page.getByRole('cell', { name: 'gpt-4o' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Reject' })).toHaveCount(0);

    await page.goto('/admin/catalog');
    await expect(page.getByRole('button', { name: /gpt-4o/ })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Disable' })).toHaveCount(0);
    await expect(page.getByRole('spinbutton')).toBeDisabled();
  });

  test('FINANCE adjusts credit and changes nothing else', async ({ page, api }) => {
    await signInAs(api, 'FINANCE');

    await openCustomer(page, CUSTOMER.email);
    await expect(page.getByText('Adjust credit')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save role' })).toHaveCount(0);

    await page.goto('/admin/proposals');
    await expect(page.getByRole('cell', { name: 'gpt-4o' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve' })).toHaveCount(0);
  });

  test('ADMIN grants a role and sees the result', async ({ page, api }) => {
    await signInAs(api, 'ADMIN');
    api.on('PUT', `/v1/admin/users/${CUSTOMER.id}/role`, (call) => ({
      json: { ...CUSTOMER, role: (call.body as { role: UserRole }).role },
    }));

    await openCustomer(page, CUSTOMER.email);
    const save = page.getByRole('button', { name: 'Save role' });
    await expect(save).toBeDisabled();
    await page.getByLabel('Role').selectOption('FINANCE');

    // Declining the confirm sends nothing...
    page.once('dialog', (d) => void d.dismiss());
    await save.click();
    expect(api.callsTo('PUT', `/v1/admin/users/${CUSTOMER.id}/role`)).toEqual([]);

    // ...accepting it changes the role.
    page.once('dialog', (d) => {
      expect(d.message()).toContain('from USER to FINANCE');
      void d.accept();
    });
    await save.click();
    await expect(save).toBeDisabled();
    expect(api.callsTo('PUT', `/v1/admin/users/${CUSTOMER.id}/role`)[0].body).toEqual({ role: 'FINANCE' });
    await page.getByRole('button', { name: 'Close' }).click();
    await expect(page.getByRole('row', { name: /grace@example.com/ })).toContainText('FINANCE');
  });

  test('a refused role change shows the server\'s reason', async ({ page, api }) => {
    await signInAs(api, 'ADMIN');
    api.on('PUT', `/v1/admin/users/${CUSTOMER.id}/role`,
      problem(409, 'a staff role needs an active account with a verified email'));

    await openCustomer(page, CUSTOMER.email);
    await page.getByLabel('Role').selectOption('SUPPORT');
    page.once('dialog', (d) => void d.accept());
    await page.getByRole('button', { name: 'Save role' }).click();

    await expect(page.getByText('needs an active account with a verified email')).toBeVisible();
  });

  test('an admin is not offered a change to their own role', async ({ page, api }) => {
    await signInAs(api, 'ADMIN');
    const me = { ...CUSTOMER, id: makeUser().id, email: 'ada@example.com', role: 'ADMIN' as const };
    api.on('GET', '/v1/admin/users', () => ({ json: { users: [me] } }));

    await openCustomer(page, me.email);

    await expect(page.getByText("You can't change your own role")).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save role' })).toHaveCount(0);
  });

  test('a customer is bounced from the console', async ({ page, api }) => {
    await api.signIn();

    await page.goto('/admin/users');

    await expect(page).toHaveURL('/dashboard');
    await expect(page.getByRole('heading', { name: 'Admin' })).toHaveCount(0);
  });
});
