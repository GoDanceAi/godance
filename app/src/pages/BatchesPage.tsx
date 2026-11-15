import { Link } from 'react-router-dom';
import BatchList from '../components/batch/BatchList';
import RecipesTabs from '../components/RecipesTabs';

const PAGE_SIZE = 24;

/** /batches — every batch the user has started, newest first. It sits under Recipes. */
export default function BatchesPage() {
  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-fg">Batches</h1>
        <RecipesTabs />
      </div>
      <p className="mt-2 text-sm text-fg-muted">
        A batch runs one of your recipes over the rows of a table. Start one from a recipe's page with Run as batch.
      </p>
      <BatchList
        pageSize={PAGE_SIZE}
        empty={(
          <div className="mt-6 card py-12 text-center">
            <p className="font-semibold text-fg">No batches yet.</p>
            <p className="mt-1 text-sm text-fg-muted">
              Open one of your recipes and choose Run as batch to run it over a table.
            </p>
            <div className="mt-5 flex justify-center">
              <Link to="/recipes" className="btn btn-primary btn-sm">Open your recipes</Link>
            </div>
          </div>
        )}
      />
    </div>
  );
}
