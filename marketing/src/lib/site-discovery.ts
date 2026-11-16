type Env = Record<string, string | undefined>;
/** Marketing rollout only; neither account permission nor provider health. */
export function discoveryEnabled(env: Env = { ...import.meta.env, ...globalThis.process?.env }): boolean {
  const value = env.PUBLIC_SITE_DISCOVERY_ENABLED?.trim().toLowerCase() ?? '';
  if (value === '' || value === 'false') return false;
  if (value === 'true') return true;
  throw new Error('PUBLIC_SITE_DISCOVERY_ENABLED must be "true" or "false" (empty defaults to false).');
}
