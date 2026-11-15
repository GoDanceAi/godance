import type { BatchRowStatus, BatchStatus } from '../../lib/api';
import { BATCH_STATUS_LABEL, ROW_STATUS_LABEL } from '../../lib/batches';

const BATCH_TONE: Record<BatchStatus, string> = {
  QUEUED: 'pill-amber',
  RUNNING: 'pill-violet',
  PAUSED: 'pill-amber',
  DONE: 'pill-emerald',
  CANCELLED: '',
};

const ROW_TONE: Record<BatchRowStatus, string> = {
  PENDING: '',
  RUNNING: 'pill-violet',
  DONE: 'pill-emerald',
  FAILED: 'pill-rose',
  SKIPPED: '',
};

/** A batch's status as a pill, in the colours a job's status has. */
export default function BatchStatusPill({ status }: { status: BatchStatus }) {
  return <span className={`pill ${BATCH_TONE[status] ?? ''}`}>{BATCH_STATUS_LABEL[status] ?? status}</span>;
}

/** One row's status. */
export function RowStatusPill({ status }: { status: BatchRowStatus }) {
  return <span className={`pill ${ROW_TONE[status] ?? ''}`}>{ROW_STATUS_LABEL[status] ?? status}</span>;
}
