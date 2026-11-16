import { Link } from 'react-router-dom';
import {
  publicProfileUrl,
  publicRecipeUrl,
  reportLink,
  runPublishedLink,
  runsText,
  type PublicRecipe,
} from '../../lib/community';

/**
 * A published recipe in the app: its first example, title, author, model and
 * how often others ran it, with "Run this" in the Studio, its public page,
 * and a way to report it.
 */
export default function PublicRecipeCard({ recipe }: { recipe: PublicRecipe }) {
  const example = recipe.examples[0];
  const isVideo = recipe.modality === 'video';
  return (
    <article aria-label={recipe.title} className="card flex flex-col gap-3 p-0 overflow-hidden" data-public-recipe={recipe.public_id}>
      <div className="relative bg-bg-muted">
        {example && example.content_type.startsWith('image/') ? (
          <img src={example.url} alt={recipe.title} loading="lazy" className="block aspect-[4/3] w-full object-cover" />
        ) : example ? (
          <video src={example.url} muted playsInline preload="metadata" aria-label={recipe.title}
                 className="block aspect-[4/3] w-full object-cover" />
        ) : (
          <div aria-hidden="true" className="aspect-[4/3] w-full" />
        )}
        {isVideo && (
          <span className="absolute left-2 bottom-2 rounded-full bg-black/70 px-2 py-0.5 text-[11px] font-semibold text-white">Video</span>
        )}
      </div>
      <div className="flex flex-1 flex-col gap-1 px-4 pb-4">
        <h3 className="break-words font-semibold text-fg">{recipe.title}</h3>
        <p className="text-xs text-fg-subtle">
          by{' '}
          <a href={publicProfileUrl(recipe.author.handle)} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:underline">
            @{recipe.author.handle}
          </a>
          <span aria-hidden="true"> · </span>
          <span className="break-all font-mono text-[11px]">{recipe.model}</span>
        </p>
        <p className="text-xs text-fg-subtle">{runsText(recipe)}</p>
        <div className="mt-auto flex flex-wrap items-center gap-2 pt-2">
          <Link to={runPublishedLink(recipe)} className="btn btn-primary btn-sm">Run this</Link>
          <a href={publicRecipeUrl(recipe.public_id)} target="_blank" rel="noopener noreferrer" className="btn btn-ghost btn-sm">
            Its page
          </a>
          <Link to={reportLink('published_recipe', recipe.public_id)} className="ml-auto text-xs text-fg-subtle hover:text-fg">
            Report
          </Link>
        </div>
      </div>
    </article>
  );
}
