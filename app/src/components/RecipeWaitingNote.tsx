import { recipeLinkOf } from '../lib/recipe';

const WORDING = {
  'sign-in': 'Sign in and the recipe you picked from the gallery will be ready to run.',
  'sign-up': 'Create your account and the recipe you picked from the gallery will be ready to run.',
} as const;

/**
 * One fixed line on the sign-in and sign-up pages when the visitor is on the
 * way to a recipe from the gallery. The words are the app's own: nothing from
 * the link, not even the recipe's name, is shown.
 */
export default function RecipeWaitingNote({ destination, page }: {
  destination: string | null;
  page: keyof typeof WORDING;
}) {
  if (destination === null || recipeLinkOf(destination) === null) return null;
  return (
    <p className="mt-4 rounded-md border border-border bg-surface-alt px-3 py-2 text-sm text-fg-muted">
      {WORDING[page]}
    </p>
  );
}
