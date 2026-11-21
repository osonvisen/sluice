import { DeadReason, JobStatus } from '../../core/api/generated';
import { StatusTone } from '../../shared/status-chip/status-chip';
import { formatRelativeTime } from '../../shared/format/date-time';

export const JOB_STATUSES: readonly JobStatus[] = Object.values(JobStatus);

export function isJobStatus(value: string | null | undefined): value is JobStatus {
  return JOB_STATUSES.some((status) => status === value);
}

const TONES: Readonly<Record<JobStatus, StatusTone>> = {
  [JobStatus.Pending]: 'neutral',
  [JobStatus.Running]: 'info',
  [JobStatus.Succeeded]: 'success',
  [JobStatus.Failed]: 'warning',
  [JobStatus.Dead]: 'error',
};

export function statusTone(status: JobStatus): StatusTone {
  return TONES[status];
}

/** A Transloco key and its parameters. */
export interface Text {
  key: string;
  params: Record<string, string | number>;
}

export interface AttemptState {
  status: JobStatus;
  deadReason?: DeadReason | null;
  attempts: number;
  maxAttempts: number;
  nextRunAt: string;
}

/**
 * Where a job is in its attempts, in words a customer understands: "Attempt 1 of 5 failed – next
 * attempt in 3 minutes" rather than `FAILED, attempts=1, next_run_at=…` (ADR 0009 status model).
 */
export function describeAttempts(job: AttemptState, now: Date, language: string): Text {
  const counts = { attempt: job.attempts, max: job.maxAttempts };
  const due = new Date(job.nextRunAt).getTime() > now.getTime();
  const when = formatRelativeTime(job.nextRunAt, now, language);
  switch (job.status) {
    case JobStatus.Pending:
      return due
        ? { key: 'jobs.attempts.scheduled', params: { when } }
        : { key: 'jobs.attempts.queued', params: {} };
    case JobStatus.Running:
      return { key: 'jobs.attempts.running', params: counts };
    case JobStatus.Failed:
      return due
        ? { key: 'jobs.attempts.retrying', params: { ...counts, when } }
        : { key: 'jobs.attempts.retryingNow', params: counts };
    case JobStatus.Succeeded:
      return { key: 'jobs.attempts.succeeded', params: counts };
    case JobStatus.Dead:
      return job.deadReason === DeadReason.Permanent
        ? { key: 'jobs.attempts.deadPermanent', params: counts }
        : { key: 'jobs.attempts.deadExhausted', params: counts };
  }
}

/** Only failed and dead jobs can be run again (the API answers 409 otherwise). */
export function canRunAgain(status: JobStatus): boolean {
  return status === JobStatus.Failed || status === JobStatus.Dead;
}
