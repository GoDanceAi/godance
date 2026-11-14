import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, type MediaModality } from '../lib/api';
import type { OwnRecipe } from '../lib/studio-prefill';
import { createRecipe, saveVersion, TITLE_MAX_LENGTH, type RecipeSetup } from '../lib/user-recipes';

type Saved =
  | { kind: 'created'; id: string }
  | { kind: 'version'; id: string; number: number }
  | { kind: 'unchanged'; number: number };

/** What the API said, when it gave a sentence of its own; otherwise ours. */
function refusal(err: unknown, fallback: string): string {
  const said = err instanceof ApiError && err.status >= 400 && err.status < 500 && !err.message.startsWith('HTTP ');
  return said ? (err as ApiError).message : fallback;
}

/**
 * Keeps the Studio's form as one of the user's recipes. With no recipe in
 * the form it asks for a title and makes one; with one, it saves the form as
 * that recipe's next version, which the API only writes when something
 * changed.
 *
 * `setup` is what would be saved, or null while the form can't be (no model
 * yet, an empty prompt, a template that can't be rendered).
 */
export default function SaveRecipe({ own, setup, modality, suggestedTitle, galleryRecipe, onSaved }: {
  own: OwnRecipe | null;
  setup: RecipeSetup | null;
  modality: MediaModality;
  suggestedTitle: string;
  galleryRecipe: string | null;            // the gallery recipe the form came from, by slug
  onSaved: (own: OwnRecipe) => void;
}) {
  const [naming, setNaming] = useState(false);
  const [title, setTitle] = useState('');
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState<Saved | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const startNaming = () => {
    setTitle(suggestedTitle.slice(0, TITLE_MAX_LENGTH));
    setSaved(null);
    setProblem(null);
    setNaming(true);
  };

  const create = async () => {
    if (!setup || title.trim() === '') return;
    setBusy(true);
    setProblem(null);
    try {
      const recipe = await createRecipe({
        ...setup,
        title: title.trim(),
        modality,
        ...(galleryRecipe ? { source_gallery_recipe: galleryRecipe } : {}),
      });
      setNaming(false);
      setSaved({ kind: 'created', id: recipe.id });
      onSaved({ recipe, version: recipe.latest_version });
    } catch (err) {
      setProblem(refusal(err, "Couldn't save this recipe. Try again."));
    } finally {
      setBusy(false);
    }
  };

  const saveNext = async () => {
    if (!setup || !own) return;
    setBusy(true);
    setProblem(null);
    setSaved(null);
    try {
      const latest = own.recipe.latest_version;
      const version = await saveVersion(own.recipe.id, setup);
      // The API answers with the latest version as it stands when nothing changed.
      setSaved(version.id === latest.id
        ? { kind: 'unchanged', number: version.number }
        : { kind: 'version', id: own.recipe.id, number: version.number });
      onSaved({ recipe: { ...own.recipe, latest_version: version }, version });
    } catch (err) {
      setProblem(refusal(err, "Couldn't save a new version. Try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2">
      {own ? (
        <button type="button" onClick={() => void saveNext()} disabled={!setup || busy} className="btn btn-secondary btn-sm">
          {busy ? 'Saving…' : 'Save as new version'}
        </button>
      ) : !naming ? (
        <button type="button" onClick={startNaming} disabled={!setup} className="btn btn-secondary btn-sm"
                title="Keep this prompt, model and settings as a recipe of your own">
          Save as recipe
        </button>
      ) : (
        <div role="group" aria-label="Save as recipe" className="rounded-md border border-border bg-surface-alt p-3">
          <label htmlFor="studio-recipe-title" className="block text-xs font-medium text-fg">Recipe title</label>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <input
              id="studio-recipe-title"
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              // Enter saves the recipe; it must not submit the form around it, which would generate.
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  void create();
                }
              }}
              maxLength={TITLE_MAX_LENGTH}
              autoComplete="off"
              autoFocus
              className="input min-w-0 flex-1"
            />
            <button type="button" onClick={() => void create()} disabled={busy || !setup || title.trim() === ''}
                    className="btn btn-primary btn-sm">
              {/* Not "Save recipe": that is the gallery line's bookmark. */}
              {busy ? 'Saving…' : 'Create recipe'}
            </button>
            <button type="button" onClick={() => setNaming(false)} disabled={busy} className="btn btn-ghost btn-sm">
              Cancel
            </button>
          </div>
        </div>
      )}
      {/* Announced, but not a status region: the Studio's one status region is the result's. */}
      <p aria-live="polite" className="text-xs text-fg-muted">
        {saved?.kind === 'created' && (
          <>Saved to <Link to={`/recipes/${saved.id}`} className="font-semibold text-brand-600 hover:underline">your recipes</Link>. Runs from here count as its attempts.</>
        )}
        {saved?.kind === 'version' && (
          <>Saved as <Link to={`/recipes/${saved.id}`} className="font-semibold text-brand-600 hover:underline">version {saved.number}</Link>.</>
        )}
        {saved?.kind === 'unchanged' && <>Nothing has changed since version {saved.number}, so no new version was made.</>}
      </p>
      {problem && <p role="alert" className="text-xs text-danger">{problem}</p>}
    </div>
  );
}
