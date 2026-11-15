import { Link } from 'react-router-dom';
import BatchList from './BatchList';

const PAGE_SIZE = 5;

/** The batches of one recipe, on the recipe's page: newest first, with the way to start another. */
export default function RecipeBatches({ recipeId }: { recipeId: string }) {
  return (
    <section aria-labelledby="recipe-batches" className="mt-8">
      <h2 id="recipe-batches" className="text-sm font-semibold text-fg">Batches</h2>
      <BatchList
        recipeId={recipeId}
        pageSize={PAGE_SIZE}
        empty={(
          <p className="mt-3 text-sm text-fg-muted">
            This recipe has not been run as a batch yet.{' '}
            <Link to={`/recipes/${recipeId}/batch`} className="text-brand-600 hover:underline">Run it over a table</Link>{' '}
            to make one generation for every row.
          </p>
        )}
      />
    </section>
  );
}
