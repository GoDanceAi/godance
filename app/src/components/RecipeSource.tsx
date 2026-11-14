import type { GalleryRecipe, RecipeLang } from '../lib/recipe';
import { Icon } from './Brand';

/** Only Chinese text is marked: the page itself is in English. */
function langAttr(lang: RecipeLang): string | undefined {
  return lang === 'zh' ? 'zh-Hans' : undefined;
}

/**
 * The line saying the form was filled from a gallery recipe, with the
 * recipe's title linking to its page, a control that saves the recipe to
 * come back to, and a switch between the English and Chinese prompt when the
 * recipe has both.
 *
 * `saved` is whether the user has saved this recipe; null leaves the control
 * out (the list of saved recipes could not be read). `saveError` is what a
 * refused save said.
 */
export default function RecipeSource({ recipe, lang, onLanguage, saved = null, onToggleSaved, saveError = null }: {
  recipe: GalleryRecipe;
  lang: RecipeLang;
  onLanguage: (lang: RecipeLang) => void;
  saved?: boolean | null;
  onToggleSaved?: () => void;
  saveError?: string | null;
}) {
  const titleLang: RecipeLang = recipe.title[lang] !== null ? lang : recipe.title.en !== null ? 'en' : 'zh';
  const title = recipe.title[titleLang] ?? '';
  const bilingual = recipe.prompt.en !== null && recipe.prompt.zh !== null;

  return (
    <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 rounded-md border border-border bg-surface-alt px-3 py-2">
      <p className="min-w-0 text-sm text-fg-muted">
        From the gallery:{' '}
        {recipe.url ? (
          // A new tab, so reading the recipe's page keeps the form as it is.
          <a href={recipe.url} target="_blank" rel="noopener noreferrer" lang={langAttr(titleLang)}
             className="font-medium text-brand-600 hover:underline">
            {title}
          </a>
        ) : (
          <span lang={langAttr(titleLang)} className="font-medium text-fg">{title}</span>
        )}
      </p>
      {saved !== null && onToggleSaved && (
        <button
          type="button"
          aria-pressed={saved}
          title={saved ? 'In your saved recipes. Select to remove it.' : 'Keep this recipe to come back to'}
          onClick={onToggleSaved}
          className="btn btn-ghost btn-sm shrink-0 px-2 py-1"
        >
          <Icon name={saved ? 'bookmarkFilled' : 'bookmark'} size={14} />
          {saved ? 'Recipe saved' : 'Save recipe'}
        </button>
      )}
      {bilingual && (
        <div role="group" aria-label="Prompt language"
             className="inline-flex shrink-0 rounded-md border border-border bg-surface p-0.5">
          {(['en', 'zh'] as const).map((option) => (
            <button
              key={option}
              type="button"
              lang={langAttr(option)}
              aria-pressed={lang === option}
              onClick={() => onLanguage(option)}
              className={`rounded-sm px-2.5 py-1 text-xs font-semibold transition-colors ${
                lang === option ? 'bg-brand-600 text-white' : 'text-fg-muted hover:text-fg'}`}
            >
              {option === 'en' ? 'English' : '中文'}
            </button>
          ))}
        </div>
      )}
      {saveError && <p role="alert" className="basis-full text-xs text-danger">{saveError}</p>}
    </div>
  );
}
