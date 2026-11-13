/**
 * The spending cap. Every paid call, by any stage, goes through one budget:
 * it holds the call's projected cost before the call is made, and refuses
 * the hold when what has been spent, what is in flight and this call
 * together would pass the cap.
 *
 * The cap covers the whole work folder, not one run: what earlier runs
 * spent is read back from the ledger, so running again with the same cap
 * can never spend more in total.
 */
import { estimateTokens } from './pricing.js';
import { readJson, writeJson } from './work.js';

export class BudgetExceeded extends Error {
  constructor(wanted, budget) {
    super(
      `budget: holding ${cents(wanted)} more would pass the cap of ${cents(budget.cap)} ` +
        `(${cents(budget.spent)} spent, ${cents(budget.held)} in flight)`,
    );
    this.name = 'BudgetExceeded';
  }
}

/** 6 as "6 cents", 0.42 as "0.42 cents". */
export const cents = (amount) => `${Number.isInteger(amount) ? amount : amount.toFixed(2)} cent${amount === 1 ? '' : 's'}`;

/**
 * @param {{ cap: number, spent?: number, held?: number }} start `held` is what
 *   jobs accepted by an earlier, interrupted run still hold.
 */
export function createBudget({ cap, spent = 0, held = 0 }) {
  if (!Number.isFinite(cap) || cap < 0) throw new Error('budget: the cap must be a number of cents, zero or more');
  const budget = {
    cap,
    spent,
    held,
    get remaining() {
      return Math.max(0, budget.cap - budget.spent - budget.held);
    },
    /** Whether a call that holds this much may start. */
    canHold: (amount) => budget.spent + budget.held + amount <= budget.cap,
    /**
     * Hold a call's projected cost. Throws BudgetExceeded, before anything is
     * spent, when the cap would be passed. Settle or release the hold after.
     */
    hold(amount) {
      if (!budget.canHold(amount)) throw new BudgetExceeded(amount, budget);
      budget.held += amount;
      return holdOf(budget, amount);
    },
    /** Take over a hold made by an earlier run; it is already counted in `held`. */
    adopt: (amount) => holdOf(budget, amount),
  };
  return budget;
}

function holdOf(budget, amount) {
  let open = true;
  const close = () => {
    if (!open) throw new Error('budget: this hold was already settled or released');
    open = false;
    budget.held -= amount;
  };
  return {
    amount,
    /** The call was made and cost this much. */
    settle(actual) {
      close();
      budget.spent += actual;
    },
    /** The call was not made, or failed and was refunded. */
    release: close,
  };
}

/**
 * The ledger: every cent spent in this work folder, one line per paid call,
 * saved as soon as each is known.
 */
export async function openLedger(file, { now = () => new Date() } = {}) {
  const data = await readJson(file, { spent_cents: 0, events: [] });
  return {
    get spent() {
      return data.spent_cents;
    },
    events: data.events,
    /** @param {{ kind: string, ref: string, cents: number, model?: string, job_id?: string, note?: string }} event */
    async record(event) {
      data.events.push({ at: now().toISOString(), ...event });
      data.spent_cents = data.events.reduce((total, each) => total + each.cents, 0);
      await writeJson(file, data);
    },
  };
}

/**
 * One paid chat call under the budget. Holds its projected cost first, so
 * the cap is checked before anything is spent, then records what it cost.
 *
 * A chat reply does not state its price. The cost recorded is the reply's
 * token usage at the catalogue price: a close estimate, not a billed amount.
 */
export async function paidChat({ api, prices, budget, ledger, model, messages, maxTokens, kind, ref }) {
  const inputTokens = estimateTokens(messages.map((message) => message.content).join('\n'));
  const hold = budget.hold(prices.text(model, inputTokens, maxTokens));
  let reply;
  try {
    reply = await api.chat({ model, messages, maxTokens });
  } catch (error) {
    if (error.rejected) {
      hold.release();
    } else {
      // No clear answer: the call may have been billed, so it counts in full.
      hold.settle(hold.amount);
      await ledger.record({ kind, ref, model, cents: hold.amount, note: 'outcome unknown; counted at the amount held' });
    }
    throw error;
  }
  const actual = reply.usage
    ? prices.text(model, reply.usage.prompt_tokens ?? inputTokens, reply.usage.completion_tokens ?? maxTokens)
    : hold.amount;
  hold.settle(actual);
  await ledger.record({ kind, ref, model, cents: actual });
  return reply.text;
}
