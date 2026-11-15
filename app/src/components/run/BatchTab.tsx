import { Link } from 'react-router-dom';
import type { HandOver } from '../../lib/hand-over';

/**
 * "Batch": the one way that needs nothing outside the app. A recipe runs
 * over a pasted table or a CSV on its new-batch page; anything else has to
 * be saved as a recipe first.
 */
export default function BatchTab({ handOver }: { handOver: HandOver }) {
  if (handOver.batchRecipeId === null) {
    return (
      <p className="text-sm text-fg-muted">
        A batch runs one of your recipes over a table, one row for each run, with no code and no key. This is not
        a saved recipe yet: save it as a recipe in the Studio, then choose “Run as batch” on the recipe's page.
      </p>
    );
  }
  return (
    <div>
      <p className="text-sm text-fg-muted">
        Run this recipe over a pasted table or a CSV file, one row for each run, with no code and no key. You see
        the total before anything starts.
      </p>
      <p className="mt-3">
        <Link to={`/recipes/${handOver.batchRecipeId}/batch`} className="btn btn-primary btn-sm">Run as a batch</Link>
      </p>
    </div>
  );
}
