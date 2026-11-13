import type { JobStatus } from '../lib/api';
import { STATUS_LABEL } from '../lib/jobs';

const TONE: Record<JobStatus, string> = {
  QUEUED: 'pill-amber',
  RUNNING: 'pill-violet',
  DONE: 'pill-emerald',
  FAILED: 'pill-rose',
  CANCELLED: '',
};

export default function JobStatusPill({ status }: { status: JobStatus }) {
  return <span className={`pill ${TONE[status] ?? ''}`}>{STATUS_LABEL[status] ?? status}</span>;
}
