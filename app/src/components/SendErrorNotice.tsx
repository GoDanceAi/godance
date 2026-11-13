import { Link } from 'react-router-dom';
import type { SendError } from '../lib/send-error';

/**
 * A refused paid request, as {@link SendError} describes it. `what` names the
 * request ("this message") and `then` what topping up lets the user do.
 */
export default function SendErrorNotice({ error, what, then, className = '' }: {
  error: SendError;
  what: string;
  then: string;
  className?: string;
}) {
  return (
    <p role="alert" className={`text-sm text-danger ${className}`}>
      {error.outOfCredit ? (
        <>
          Not enough credit for {what}.{' '}
          <Link to="/billing" className="text-brand-600 hover:text-brand-700 underline">
            Top up on the Billing page
          </Link>{' '}
          {then}.
        </>
      ) : error.message}
    </p>
  );
}
