import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAdmin } from '../../lib/admin';
import { useAuth } from '../../lib/auth';
import { ApiError } from '../../lib/api';
import { getAdminFeatures, type AdminFeatures } from '../../lib/admin-features';

export default function FeaturesPage() {
  const { role } = useAdmin();
  const { user } = useAuth();
  if (role !== 'ADMIN' || !user) {
    return <p className="text-sm text-fg-muted">Feature status is available to the ADMIN role.</p>;
  }
  // Changing accounts unmounts the old state and cancels its in-flight read.
  return <Configuration key={user.id} />;
}

function Configuration() {
  const [revision, setRevision] = useState(0);
  const [features, setFeatures] = useState<AdminFeatures | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [needsTwoFactor, setNeedsTwoFactor] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setFeatures(null);
    setError(null);
    setNeedsTwoFactor(false);
    getAdminFeatures(controller.signal).then((result) => {
      if (!controller.signal.aborted) setFeatures(result);
    }).catch((cause: unknown) => {
      if (controller.signal.aborted) return;
      const denied = cause instanceof ApiError && cause.status === 403;
      const twoFactor = denied && cause.code === 'two_factor_required';
      setNeedsTwoFactor(twoFactor);
      setError(twoFactor
        ? 'Turn on two-factor sign-in in Settings to read feature configuration.'
        : denied
          ? 'Access denied. Feature configuration requires an ADMIN session.'
          : 'Could not load feature configuration. No configuration status is available.');
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [revision]);

  const rows = features && [
    { name: 'Inputs', enabled: features.inputs.enabled, detail: `People in inputs: configured ${features.inputs.allow_people ? 'allowed' : 'not allowed'}. Account age, terms and model capabilities still apply.` },
    { name: 'Community', enabled: features.community.enabled, detail: 'Public content still requires approval and readable content. Account and moderation rules remain in force.' },
    { name: 'Bring your own key', enabled: features.byok.enabled, detail: 'At least one provider is configured when on. This does not establish key validity, provider health or supported modalities.' },
    { name: 'User routing policies', enabled: features.routing_policies.enabled, detail: `Allowlist restriction: ${features.routing_policies.allow_list_restricted ? 'configured' : 'not configured'}. This does not identify eligible accounts.` },
    { name: 'Referrals', enabled: features.referrals.enabled, detail: 'At least one reward is configured when on. Existing account and reward rules still apply.' },
    { name: 'MCP', enabled: features.mcp.enabled, detail: 'Backend configuration only. This is not a connection test or a statement that a provider or gallery is available.' },
  ];

  return (
    <section aria-labelledby="features-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="features-heading" className="text-xl font-semibold text-fg">Feature status</h2>
        <button type="button" className="btn btn-sm btn-secondary" disabled={loading}
          onClick={() => setRevision((value) => value + 1)}>
          {error ? 'Retry' : 'Refresh'}
        </button>
      </div>
      <div className="mt-4 card text-sm text-fg-muted space-y-3">
        <p>These values report backend configuration only. Customer eligibility comes from the existing per-user endpoints.</p>
        <p>Gallery and community content, and marketing build flags, are separate. Configured on does not mean a feature is available to every account or that a provider is healthy.</p>
        <p>Changes use the existing deployment process and restart, outside this console.</p>
      </div>
      {loading && <p role="status" className="mt-4 text-sm text-fg-faint">Loading feature configuration…</p>}
      {error && <p role="alert" className="mt-4 text-sm text-danger">{error}</p>}
      {needsTwoFactor && <Link to="/settings" className="mt-3 btn btn-sm btn-primary">Go to settings</Link>}
      {rows && <div className="mt-4 grid gap-4 md:grid-cols-2">
        {rows.map((row) => <article key={row.name} className="card">
          <h3 className="font-semibold text-fg">{row.name}</h3>
          <p className="mt-2"><span className={`pill ${row.enabled ? 'pill-violet' : ''}`}>Configured {row.enabled ? 'on' : 'off'}</span></p>
          <p className="mt-3 text-sm text-fg-muted">{row.detail}</p>
        </article>)}
      </div>}
    </section>
  );
}
