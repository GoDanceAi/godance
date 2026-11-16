import { useEffect, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import type { JobView, UserRecipe, UserRecipeVersion } from '../../lib/api';
import {
  codeOf,
  EXAMPLES_MAX,
  getSubmission,
  LICENCE_TERMS,
  parseTags,
  publicRecipeUrl,
  publishRecipe,
  refusal,
  unpublishRecipe,
  type OwnProfile,
  type PublishSubmission,
} from '../../lib/community';
import { useCommunity } from '../../lib/community-context';
import { listJobs } from '../../lib/jobs';
import { formatDateTime } from '../../lib/time';
import { fetchVersions } from '../../lib/user-recipes';
import Dialog from '../Dialog';
import { JobThumb } from '../JobMedia';

/** The most attempts the dialog reads to offer as examples: one page of the API's. */
const ATTEMPTS_READ = 100;

type Load = { submission: PublishSubmission | null } | { failed: true } | null;

/**
 * A recipe page's part in the community: whether the recipe is published,
 * and how that went (pending, public, rejected or removed, with the
 * moderator's reason), with Publish and Unpublish. Shown only while the
 * community is open; an account that may not publish is told why.
 */
export default function PublishPanel({ recipe }: { recipe: UserRecipe }) {
  const { open, profile } = useCommunity();
  const [load, setLoad] = useState<Load>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  // An account that may not publish has no profile, so it has never published: there is nothing to read.
  const mayPublish = open && profile?.cannot_publish === null;
  useEffect(() => {
    if (!mayPublish) return;
    let cancelled = false;
    getSubmission(recipe.id).then(
      (submission) => { if (!cancelled) setLoad({ submission }); },
      () => { if (!cancelled) setLoad({ failed: true }); },
    );
    return () => { cancelled = true; };
  }, [mayPublish, recipe.id]);

  if (!open || !profile) return null;
  const submission = load && 'submission' in load ? load.submission : null;
  const active = submission?.status === 'PENDING' || submission?.status === 'PUBLIC';

  const unpublish = async () => {
    if (!submission) return;
    const what = submission.status === 'PUBLIC'
      ? 'It leaves the community at once, and its example copies are deleted.'
      : 'It is taken out of review at once.';
    if (!window.confirm(`Unpublish “${recipe.title}”? ${what}`)) return;
    setBusy(true);
    setProblem(null);
    try {
      await unpublishRecipe(recipe.id);
      // The submission is kept, as removed by its author.
      const latest = await getSubmission(recipe.id).catch(() => null);
      setLoad({ submission: latest ?? { ...submission, status: 'REMOVED', reason: null } });
    } catch (err) {
      setProblem(refusal(err, "Couldn't unpublish this recipe. Try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="recipe-community" className="mt-8 card" data-publish-panel>
      <h2 id="recipe-community" className="text-sm font-semibold text-fg">Community</h2>
      {profile.cannot_publish === 'not_adult' && (
        <p className="mt-1 text-sm text-fg-muted" data-why-not="not_adult">Publishing recipes is for adult accounts only.</p>
      )}
      {profile.cannot_publish === 'email_not_verified' && (
        <p className="mt-1 text-sm text-fg-muted" data-why-not="email_not_verified">
          Verify your email address to publish recipes. <Link to="/settings" className="text-brand-600 hover:underline">Settings</Link> can send the link again.
        </p>
      )}
      {profile.cannot_publish === 'no_profile' && (
        <p className="mt-1 text-sm text-fg-muted" data-why-not="no_profile">
          To publish this recipe, first <Link to="/settings" className="font-medium text-brand-600 hover:underline">set up your public profile</Link>:
          recipes are published under its handle.
        </p>
      )}

      {profile.cannot_publish === null && (
        <>
          {load === null && <p className="mt-1 text-sm text-fg-faint">Loading…</p>}
          {load && 'failed' in load && (
            <p role="alert" className="mt-1 text-sm text-danger">Couldn't read whether this recipe is published.</p>
          )}
          {load && 'submission' in load && (
            <>
              <StatusLine submission={submission} />
              <div className="mt-3 flex flex-wrap items-center gap-2">
                {active ? (
                  <button type="button" onClick={() => void unpublish()} disabled={busy} className="btn btn-danger btn-sm">
                    {busy ? 'Unpublishing…' : 'Unpublish'}
                  </button>
                ) : (
                  <button type="button" onClick={() => setDialogOpen(true)} className="btn btn-primary btn-sm">
                    {submission ? 'Publish again…' : 'Publish…'}
                  </button>
                )}
              </div>
              {problem && <p role="alert" className="mt-2 text-sm text-danger">{problem}</p>}
            </>
          )}
        </>
      )}

      {dialogOpen && (
        <PublishDialog
          recipe={recipe}
          profile={profile}
          onClose={() => setDialogOpen(false)}
          onSubmitted={(next) => {
            setLoad({ submission: next });
            setDialogOpen(false);
          }}
        />
      )}
    </section>
  );
}

/** What became of the latest submission, in a line, with the moderator's reason when there is one. */
function StatusLine({ submission }: { submission: PublishSubmission | null }) {
  if (!submission) {
    return (
      <p className="mt-1 text-sm text-fg-muted" data-publish-status="none">
        Share this recipe in the community: others can see it, run it with their own values and follow you.
        A moderator approves it before anything is public.
      </p>
    );
  }
  const facts = `Version ${submission.version} · ${submission.licence}${submission.tags.length ? ` · ${submission.tags.join(', ')}` : ''}`;
  return (
    <div className="mt-1 space-y-1 text-sm" data-publish-status={submission.status.toLowerCase()} aria-live="polite">
      {submission.status === 'PENDING' && (
        <p>
          <span className="pill pill-amber">Pending review</span>{' '}
          <span className="text-fg-muted">
            Submitted {formatDateTime(submission.submitted_at)}. Nothing is public until a moderator approves it.
          </span>
        </p>
      )}
      {submission.status === 'PUBLIC' && (
        <p>
          <span className="pill pill-emerald">Public</span>{' '}
          <span className="text-fg-muted">
            Published {formatDateTime(submission.published_at ?? submission.reviewed_at ?? submission.submitted_at)}:{' '}
            <a href={publicRecipeUrl(submission.public_id)} target="_blank" rel="noopener noreferrer"
               className="font-medium text-brand-600 hover:underline">
              its page on godance.ai
            </a>
            . A new page appears there within a day.
          </span>
        </p>
      )}
      {submission.status === 'REJECTED' && (
        <p>
          <span className="pill pill-rose">Rejected</span>{' '}
          <span className="text-fg-muted">A moderator rejected it: </span>
          <span className="text-fg" data-reason>{submission.reason ?? 'no reason given'}</span>
          <span className="text-fg-muted"> You can change it and publish it again.</span>
        </p>
      )}
      {submission.status === 'REMOVED' && submission.reason !== null && (
        <p>
          <span className="pill pill-rose">Removed</span>{' '}
          <span className="text-fg-muted">A moderator removed it: </span>
          <span className="text-fg" data-reason>{submission.reason}</span>
        </p>
      )}
      {submission.status === 'REMOVED' && submission.reason === null && (
        <p>
          <span className="pill">Unpublished</span>{' '}
          <span className="text-fg-muted">You unpublished it. It is no longer in the community.</span>
        </p>
      )}
      <p className="text-xs text-fg-subtle">{facts}</p>
    </div>
  );
}

/** An attempt that may be an example: finished, its files kept, and made from no image of the user's. */
function eligible(job: JobView, version: number): boolean {
  return job.status === 'DONE' && !job.purged && (job.inputs?.length ?? 0) === 0
    && job.recipe?.version === version && ((job.outputs?.length ?? 0) > 0 || job.outputUrl !== null);
}

/**
 * Choose what to publish: the version, one to four of its finished runs as
 * examples, the licence and tags. It says what will be public and what will
 * not, then submits for review.
 */
function PublishDialog({ recipe, profile, onClose, onSubmitted }: {
  recipe: UserRecipe;
  profile: OwnProfile;
  onClose: () => void;
  onSubmitted: (submission: PublishSubmission) => void;
}) {
  const [versions, setVersions] = useState<UserRecipeVersion[]>([recipe.latest_version]);
  const [version, setVersion] = useState(recipe.latest_version.number);
  const [jobs, setJobs] = useState<JobView[] | null>(null);
  const [jobsFailed, setJobsFailed] = useState(false);
  const [chosen, setChosen] = useState<string[]>([]);
  const [licence, setLicence] = useState(profile.licences[0] ?? '');
  const [tagText, setTagText] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchVersions(recipe.id).then((list) => { if (!cancelled && list.length > 0) setVersions(list); }, () => undefined);
    listJobs({ limit: ATTEMPTS_READ, recipe: recipe.id }).then(
      (list) => { if (!cancelled) setJobs(list); },
      () => { if (!cancelled) setJobsFailed(true); },
    );
    return () => { cancelled = true; };
  }, [recipe.id]);

  const runs = useMemo(() => (jobs ?? []).filter((job) => eligible(job, version)), [jobs, version]);
  const { tags, problem: tagProblem } = parseTags(tagText);
  const full = chosen.length >= EXAMPLES_MAX;
  const canSubmit = !busy && chosen.length > 0 && licence !== '' && tagProblem === null;

  const pickVersion = (number: number) => {
    setVersion(number);
    // Examples are runs of the version published, so a new version starts the choice again.
    setChosen([]);
  };
  const toggle = (id: string) => setChosen((cur) => (cur.includes(id) ? cur.filter((each) => each !== id) : [...cur, id]));

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setProblem(null);
    try {
      const submission = await publishRecipe(recipe.id, {
        version,
        licence,
        // In the order shown: newest first.
        job_ids: runs.filter((job) => chosen.includes(job.id)).map((job) => job.id),
        ...(tags.length > 0 ? { tags } : {}),
      });
      onSubmitted(submission);
    } catch (err) {
      setProblem(codeOf(err) === 'profile_required'
        ? 'Set up your public profile in Settings first.'
        : refusal(err, "Couldn't submit this recipe. Try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog title={`Publish “${recipe.title}”`} onClose={onClose}>
      <form onSubmit={(e) => void submit(e)} aria-label="Publish recipe" className="space-y-5 text-sm">
        <div>
          <label htmlFor="publish-version" className="block font-medium text-fg">Version</label>
          <select id="publish-version" value={version} onChange={(e) => pickVersion(Number(e.target.value))} className="input mt-1.5">
            {versions.map((each) => (
              <option key={each.id} value={each.number}>
                Version {each.number}{each.number === recipe.latest_version.number ? ' (latest)' : ''} · {each.model}
              </option>
            ))}
          </select>
        </div>

        <fieldset>
          <legend className="font-medium text-fg">Examples</legend>
          <p className="mt-1 text-xs text-fg-subtle" id="publish-examples-help">
            Choose one to {EXAMPLES_MAX} runs of version {version} to show what it makes. Only finished runs of this
            version that used no image of yours can be examples, and only while their files are kept.
          </p>
          {jobsFailed && <p role="alert" className="mt-2 text-danger">Couldn't load this recipe's runs.</p>}
          {!jobsFailed && jobs === null && <p className="mt-2 text-fg-faint">Loading…</p>}
          {jobs !== null && runs.length === 0 && (
            <p className="mt-2 text-fg-muted" data-no-examples>
              No run of version {version} can be an example yet. <Link to={`/create/${recipe.modality.toLowerCase()}?recipe=${recipe.id}`}
              className="text-brand-600 hover:underline">Run it in the Studio</Link> first.
            </p>
          )}
          {runs.length > 0 && (
            <ul className="mt-2 grid grid-cols-2 gap-3 sm:grid-cols-4" aria-describedby="publish-examples-help">
              {runs.map((job) => {
                const checked = chosen.includes(job.id);
                return (
                  <li key={job.id}>
                    <label className={`block cursor-pointer rounded-md border p-1.5 ${checked ? 'border-brand-600' : 'border-border'}`}>
                      <div className="overflow-hidden rounded-sm"><JobThumb job={job} /></div>
                      <span className="mt-1.5 flex items-center gap-1.5 text-xs text-fg-muted">
                        <input
                          type="checkbox"
                          checked={checked}
                          disabled={!checked && full}
                          onChange={() => toggle(job.id)}
                          aria-label={`Use the run from ${formatDateTime(job.createdAt)} as an example`}
                          className="h-4 w-4 accent-[var(--color-primary)]"
                        />
                        {formatDateTime(job.createdAt)}
                      </span>
                    </label>
                  </li>
                );
              })}
            </ul>
          )}
          {runs.length > 0 && (
            <p className="mt-2 text-xs text-fg-subtle" aria-live="polite">
              {chosen.length} of {EXAMPLES_MAX} chosen{full ? ', the most a recipe shows' : ''}.
            </p>
          )}
        </fieldset>

        <fieldset>
          <legend className="font-medium text-fg">Licence</legend>
          <div className="mt-2 space-y-2">
            {profile.licences.map((each) => (
              <label key={each} className="flex items-start gap-2">
                <input type="radio" name="publish-licence" value={each} checked={licence === each}
                       onChange={() => setLicence(each)} className="mt-0.5 accent-[var(--color-primary)]" />
                <span>
                  <span className="font-medium text-fg">{each}</span>
                  {LICENCE_TERMS[each] && <span className="block text-xs text-fg-subtle">{LICENCE_TERMS[each]}</span>}
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        <div>
          <label htmlFor="publish-tags" className="block font-medium text-fg">Tags <span className="font-normal text-fg-subtle">(optional)</span></label>
          <input id="publish-tags" type="text" value={tagText} onChange={(e) => setTagText(e.target.value)}
                 placeholder="still-life, kitchen" autoComplete="off" autoCapitalize="none"
                 aria-describedby="publish-tags-note" className="input mt-1.5" />
          <p id="publish-tags-note" className={`mt-1.5 text-xs ${tagProblem ? 'text-danger' : 'text-fg-subtle'}`}>
            {tagProblem ?? 'Up to five, separated by commas: lowercase letters, digits and hyphens. People find recipes by them.'}
          </p>
        </div>

        <div className="grid gap-3 sm:grid-cols-2" data-publish-visibility>
          <div className="embedded-surface p-3">
            <h3 className="text-xs font-semibold text-fg">Public once approved</h3>
            <ul className="mt-1.5 list-disc space-y-0.5 pl-4 text-xs text-fg-muted" aria-label="Public once approved">
              <li>The title, and version {version}'s template, variables and their defaults</li>
              <li>Its model and settings, and its input slots without any image</li>
              <li>Copies of the examples you choose</li>
              <li>The licence and tags</li>
              <li>Your handle, display name and link, and how many follow you</li>
              <li>How many runs it gets from others</li>
            </ul>
          </div>
          <div className="embedded-surface p-3">
            <h3 className="text-xs font-semibold text-fg">Stays private</h3>
            <ul className="mt-1.5 list-disc space-y-0.5 pl-4 text-xs text-fg-muted" aria-label="Stays private">
              <li>Your other versions, and every run you don't choose</li>
              <li>What your runs cost, and your balance</li>
              <li>Your email address and account</li>
              <li>Any image you uploaded</li>
              <li>Who runs it, and who follows you</li>
            </ul>
          </div>
        </div>

        <p className="text-xs text-fg-subtle">
          A moderator reviews it first. Later versions and renames don't change what is published: publish again for that.
        </p>
        {problem && <p role="alert" className="text-danger">{problem}</p>}
        <div className="flex flex-wrap items-center justify-end gap-2">
          <button type="button" onClick={onClose} className="btn btn-ghost btn-sm">Cancel</button>
          <button type="submit" disabled={!canSubmit} className="btn btn-primary btn-sm">
            {busy ? 'Submitting…' : 'Submit for review'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
