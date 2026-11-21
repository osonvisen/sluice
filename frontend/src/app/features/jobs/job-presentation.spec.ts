import { DeadReason, JobStatus } from '../../core/api/generated';
import { AttemptState, canRunAgain, describeAttempts, statusTone } from './job-presentation';

describe('describeAttempts', () => {
  const now = new Date('2026-11-20T12:00:00Z');
  const job = (state: Partial<AttemptState>): AttemptState => ({
    status: JobStatus.Pending,
    attempts: 0,
    maxAttempts: 5,
    nextRunAt: '2026-11-20T12:00:00Z',
    ...state,
  });

  it('says when a failed job tries again, as the customer would put it', () => {
    expect(
      describeAttempts(
        job({ status: JobStatus.Failed, attempts: 2, nextRunAt: '2026-11-20T12:03:00Z' }),
        now,
        'en',
      ),
    ).toEqual({
      key: 'jobs.attempts.retrying',
      params: { attempt: 2, max: 5, when: 'in 3 minutes' },
    });
  });

  it('uses the chosen language for the time', () => {
    const text = describeAttempts(
      job({ status: JobStatus.Failed, attempts: 1, nextRunAt: '2026-11-20T12:03:00Z' }),
      now,
      'nb',
    );
    expect(text.params['when']).toBe('om 3 minutter');
  });

  it('a failed job that is already due will run shortly', () => {
    expect(describeAttempts(job({ status: JobStatus.Failed, attempts: 1 }), now, 'en').key).toBe(
      'jobs.attempts.retryingNow',
    );
  });

  it('tells permanent errors from used-up attempts', () => {
    expect(
      describeAttempts(
        job({ status: JobStatus.Dead, attempts: 1, deadReason: DeadReason.Permanent }),
        now,
        'en',
      ).key,
    ).toBe('jobs.attempts.deadPermanent');
    expect(
      describeAttempts(
        job({ status: JobStatus.Dead, attempts: 5, deadReason: DeadReason.Exhausted }),
        now,
        'en',
      ).key,
    ).toBe('jobs.attempts.deadExhausted');
  });

  it('describes waiting, running and succeeded jobs', () => {
    expect(describeAttempts(job({}), now, 'en').key).toBe('jobs.attempts.queued');
    expect(describeAttempts(job({ nextRunAt: '2026-11-20T13:00:00Z' }), now, 'en').key).toBe(
      'jobs.attempts.scheduled',
    );
    expect(describeAttempts(job({ status: JobStatus.Running, attempts: 1 }), now, 'en').key).toBe(
      'jobs.attempts.running',
    );
    expect(describeAttempts(job({ status: JobStatus.Succeeded, attempts: 1 }), now, 'en').key).toBe(
      'jobs.attempts.succeeded',
    );
  });
});

describe('job status', () => {
  it('only failed and dead jobs can run again', () => {
    expect(canRunAgain(JobStatus.Failed)).toBe(true);
    expect(canRunAgain(JobStatus.Dead)).toBe(true);
    expect(canRunAgain(JobStatus.Succeeded)).toBe(false);
    expect(canRunAgain(JobStatus.Running)).toBe(false);
    expect(canRunAgain(JobStatus.Pending)).toBe(false);
  });

  it('every status has a tone', () => {
    Object.values(JobStatus).forEach((status) => expect(statusTone(status)).toBeTruthy());
  });
});
