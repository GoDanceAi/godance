import { publicProfileUrl, publicRecipeUrl, type PublicRecipe } from '../../lib/community';
import FollowButton from './FollowButton';

/**
 * The line saying the Studio's form was filled from a community recipe: its
 * title and author, each linking to their public page (in a new tab, so the
 * form stays as it is), its licence, and a Follow button for the author.
 */
export default function PublishedSource({ recipe }: { recipe: PublicRecipe }) {
  const handle = recipe.author.handle;
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 rounded-md border border-border bg-surface-alt px-3 py-2"
         data-published-source>
      <p className="min-w-0 text-sm text-fg-muted">
        From the community:{' '}
        <a href={publicRecipeUrl(recipe.public_id)} target="_blank" rel="noopener noreferrer"
           className="font-medium text-brand-600 hover:underline">
          {recipe.title}
        </a>{' '}
        by{' '}
        <a href={publicProfileUrl(handle)} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:underline">
          @{handle}
        </a>
        <span className="whitespace-nowrap text-fg-subtle"> · {recipe.licence}</span>
      </p>
      <FollowButton handle={handle} className="btn btn-ghost btn-sm shrink-0 px-2 py-1" />
    </div>
  );
}
