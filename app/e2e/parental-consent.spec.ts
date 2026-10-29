import type { ParentalConsentView } from '../src/lib/api';
import { authResponse, expect, makeUser, problem, storedToken, test } from './mock-api';

const PASSWORD = 'correct-horse-battery';
// Twelve this year, whatever year the suite runs in.
const KID_YEAR = new Date().getUTCFullYear() - 12;

const WAITING_KID = makeUser({
  fullName: 'Kim Lee',
  email: 'kim@example.com',
  emailVerified: false,
  creditBalanceCents: 0,
  ageStatus: 'CONSENT_PENDING',
  parentEmailMasked: 'p***@family.test',
});

// Stands in for the hosted Stripe Checkout page the API hands back.
const CHECKOUT = 'https://checkout.test/pay/cs_consent_e2e';

const VIEW: ParentalConsentView = {
  childFirstName: 'Kim',
  childEmailMasked: 'k***@example.com',
  minTopupCents: 1_00,
  maxTopupCents: 1_000_00,
  currency: 'usd',
  expiresAt: '2030-01-08T00:00:00Z',
  notice: {
    collected: [
      'Their email address and name',
      'Their birth month and year',
      'The IP addresses of the devices they use',
      'Usage and billing records: which AI models they used, when, and what it cost',
      'The prompts they write for images and videos, kept for 90 days',
    ],
    notStored: 'Chat text is not stored: the Godance app sends it to get an answer and does not keep it.',
    sharing: 'To produce answers, images and videos, what your child types is sent to third-party AI model providers, the companies that run the models.',
    noSale: "Godance does not sell your child's data and does not use it for advertising.",
    parentRights: "You can review or delete your child's data, or withdraw your consent, at any time by emailing hello@godance.ai.",
    contactEmail: 'hello@godance.ai',
    privacyPolicyUrl: 'https://godance.ai/legal/privacy',
  },
};

test.describe('signup under 16', () => {
  test('asks for a parent’s email, says why, and lands on the waiting screen', async ({ page, api }) => {
    api.on('POST', '/v1/auth/signup', { status: 201, json: authResponse('tok-kid') }).mockUser(WAITING_KID);

    await page.goto('/signup');
    await page.getByLabel('Full name').fill('Kim Lee');
    await page.getByLabel('Email', { exact: true }).fill('kim@example.com');
    await page.getByLabel('Password').fill(PASSWORD);
    // A neutral age screen: nothing about parents until a birth date is chosen.
    await expect(page.getByLabel("Parent or guardian's email")).toHaveCount(0);
    await page.getByLabel('Birth month').selectOption('June');
    await page.getByLabel('Birth year').selectOption(String(KID_YEAR));

    await expect(page.getByText('a parent or guardian needs to approve your account')).toBeVisible();
    await page.getByLabel("Parent or guardian's email").fill('parent@family.test');
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByRole('heading', { name: "Waiting for your parent's approval" })).toBeVisible();
    await expect(page).toHaveURL('/dashboard');
    await expect(page.getByText('p***@family.test')).toBeVisible();
    expect(await storedToken(page)).toBe('tok-kid');
    expect(api.callsTo('POST', '/v1/auth/signup')[0].body).toEqual({
      email: 'kim@example.com',
      password: PASSWORD,
      fullName: 'Kim Lee',
      birthMonth: `${KID_YEAR}-06`,
      parentEmail: 'parent@family.test',
    });
  });

  test('refuses the child’s own address as the parent’s', async ({ page, api }) => {
    await page.goto('/signup');
    await page.getByLabel('Full name').fill('Kim Lee');
    await page.getByLabel('Email', { exact: true }).fill('kim@example.com');
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByLabel('Birth month').selectOption('June');
    await page.getByLabel('Birth year').selectOption(String(KID_YEAR));
    await page.getByLabel("Parent or guardian's email").fill('KIM@example.com');
    await page.getByRole('button', { name: 'Create account' }).click();

    await expect(page.getByText('must be different from yours')).toBeVisible();
    await expect(page).toHaveURL('/signup');
    expect(api.callsTo('POST', '/v1/auth/signup')).toHaveLength(0);
  });
});

test.describe('waiting for a parent', () => {
  test('every signed-in page shows the waiting screen and no app navigation', async ({ page, api }) => {
    await api.signIn(WAITING_KID);

    await page.goto('/chat');

    await expect(page.getByRole('heading', { name: "Waiting for your parent's approval" })).toBeVisible();
    await expect(page).toHaveURL('/chat');
    await expect(page.getByRole('link', { name: 'API keys' })).toHaveCount(0);
    await expect(page.getByPlaceholder('Ask anything')).toHaveCount(0);
    // The chat page never mounted, so it never asked for models.
    expect(api.callsTo('GET', '/v1/models')).toHaveLength(0);
  });

  test('emails the parent again', async ({ page, api }) => {
    await api.signIn(WAITING_KID);
    api.on('POST', '/v1/auth/parental-consent/resend', { status: 204 });

    await page.goto('/dashboard');
    await page.getByRole('button', { name: 'Email them again' }).click();

    await expect(page.getByRole('status')).toContainText('Sent a new link');
    const [resend] = api.callsTo('POST', '/v1/auth/parental-consent/resend');
    expect(resend.headers.authorization).toBe('Bearer e2e-session-token');
  });

  test('a resend too soon shows the server’s reason', async ({ page, api }) => {
    await api.signIn(WAITING_KID);
    api.on('POST', '/v1/auth/parental-consent/resend',
      problem(429, 'We emailed your parent less than a minute ago.'));

    await page.goto('/dashboard');
    await page.getByRole('button', { name: 'Email them again' }).click();

    await expect(page.getByRole('alert')).toHaveText('We emailed your parent less than a minute ago.');
  });

  test('signing out goes to /login', async ({ page, api }) => {
    await api.signIn(WAITING_KID);

    await page.goto('/dashboard');
    await page.getByRole('main').getByRole('button', { name: 'Sign out' }).click();

    await expect(page).toHaveURL('/login');
    expect(await storedToken(page)).toBeNull();
  });
});

test.describe('parent consent page', () => {
  test('shows the notice and sends the parent to checkout for the amount they pick', async ({ page, api }) => {
    // The parent's browser may be signed in to Godance itself: the page must not use that session.
    await api.signIn(makeUser());
    api.on('GET', '/v1/auth/parental-consent', { json: VIEW })
      .on('POST', '/v1/auth/parental-consent/checkout',
        { status: 201, json: { sessionId: 'cs_consent_e2e', checkoutUrl: CHECKOUT, amountCents: 25_00 } });
    await page.route('https://checkout.test/**', (route) =>
      route.fulfill({ contentType: 'text/html', body: '<h1>Test checkout</h1>' }));

    await page.goto('/parent-consent?token=pc-valid');

    await expect(page.getByRole('heading', { name: "Approve Kim's Godance account" })).toBeVisible();
    await expect(page.getByText('Kim (k***@example.com) signed up for Godance')).toBeVisible();
    await expect(page.getByText('kept for 90 days')).toBeVisible();
    await expect(page.getByText('Chat text is not stored')).toBeVisible();
    await expect(page.getByText('third-party AI model providers')).toBeVisible();
    await expect(page.getByText('does not sell your child')).toBeVisible();
    await expect(page.getByText('by emailing hello@godance.ai')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Read the full privacy policy' }))
      .toHaveAttribute('href', 'https://godance.ai/legal/privacy');

    await page.getByRole('button', { name: '$25', exact: true }).click();
    await expect(page.getByText("You'll pay $25.00")).toBeVisible();
    await page.getByRole('button', { name: 'Approve and add credit' }).click();

    await expect(page).toHaveURL(CHECKOUT);
    await expect(page.getByRole('heading', { name: 'Test checkout' })).toBeVisible();
    const [view] = api.callsTo('GET', '/v1/auth/parental-consent');
    expect(view.query.get('token')).toBe('pc-valid');
    expect(view.headers.authorization).toBeUndefined();
    const [checkout] = api.callsTo('POST', '/v1/auth/parental-consent/checkout');
    expect(checkout.body).toEqual({ token: 'pc-valid', amountCents: 25_00 });
    expect(checkout.headers.authorization).toBeUndefined();
  });

  test('an amount outside the bounds cannot be paid', async ({ page, api }) => {
    api.on('GET', '/v1/auth/parental-consent', { json: VIEW });

    await page.goto('/parent-consent?token=pc-valid');
    const amount = page.getByRole('spinbutton');
    const approve = page.getByRole('button', { name: 'Approve and add credit' });

    await amount.fill('0');
    await expect(approve).toBeDisabled();
    await amount.fill('1001');
    await expect(approve).toBeDisabled();
    await amount.fill('7');
    await expect(approve).toBeEnabled();
    expect(api.callsTo('POST', '/v1/auth/parental-consent/checkout')).toHaveLength(0);
  });

  test('a checkout the API refuses shows why and stays on the page', async ({ page, api }) => {
    api.on('GET', '/v1/auth/parental-consent', { json: VIEW })
      .on('POST', '/v1/auth/parental-consent/checkout', problem(502, 'payment provider unavailable'));

    await page.goto('/parent-consent?token=pc-valid');
    await page.getByRole('button', { name: 'Approve and add credit' }).click();

    await expect(page.getByRole('alert')).toHaveText('payment provider unavailable');
    await expect(page.getByRole('button', { name: 'Approve and add credit' })).toBeEnabled();
  });

  test('an expired link says so and offers no payment', async ({ page, api }) => {
    api.on('GET', '/v1/auth/parental-consent', problem(410,
      'This approval link has expired. Ask your child to send a new one from the Godance app.'));

    await page.goto('/parent-consent?token=pc-expired');

    await expect(page.getByRole('heading', { name: 'This link has expired.' })).toBeVisible();
    await expect(page.getByText('Ask your child to send a new one')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve and add credit' })).toHaveCount(0);
  });

  test('a used link says it can’t be used', async ({ page, api }) => {
    api.on('GET', '/v1/auth/parental-consent', problem(404,
      "This approval link isn't valid. It may have been used already, or a newer one may have been sent."));

    await page.goto('/parent-consent?token=pc-used');

    await expect(page.getByRole('heading', { name: "This link can't be used." })).toBeVisible();
    await expect(page.getByText('It may have been used already')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve and add credit' })).toHaveCount(0);
  });

  test('back from a paid checkout, it thanks the parent', async ({ page, api }) => {
    await page.goto('/parent-consent?status=approved');

    await expect(page.getByRole('heading', { name: "Thank you. You've approved the account." })).toBeVisible();
    expect(api.calls).toHaveLength(0);
  });

  test('back from a cancelled checkout, it says nothing was paid', async ({ page }) => {
    await page.goto('/parent-consent?status=cancelled');

    await expect(page.getByRole('heading', { name: 'No payment was made.' })).toBeVisible();
    await expect(page.getByText('open the link in the email again')).toBeVisible();
  });
});

test.describe('social sign-in', () => {
  test('carries the Terms and an age attestation', async ({ page }) => {
    await page.goto('/login');

    const line = page.getByText('By continuing with Google, Apple or X');
    await expect(line).toContainText('and confirm you are 16 or older');
    await expect(line.getByRole('link', { name: 'Terms' })).toHaveAttribute('href', 'https://godance.ai/legal/terms');
    await expect(line.getByRole('link', { name: 'Privacy Policy' }))
      .toHaveAttribute('href', 'https://godance.ai/legal/privacy');
  });
});
