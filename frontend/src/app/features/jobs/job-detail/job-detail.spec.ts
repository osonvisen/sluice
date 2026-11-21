import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { render, screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { Observable, of, throwError } from 'rxjs';
import {
  AttemptOutcome,
  DeadReason,
  GetJobResponse,
  JobStatus,
  JobsService,
  LineOutcome,
  RetryJobRequestParams,
} from '../../../core/api/generated';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permissions';
import { fakeAuth } from '../../../testing/fakes';
import { translocoTesting } from '../../../testing/i18n';
import { JobDetail } from './job-detail';

const ID = '22222222-2222-2222-2222-222222222222';

const deadJob: GetJobResponse = {
  id: ID,
  type: 'order.import',
  status: JobStatus.Dead,
  deadReason: DeadReason.Permanent,
  attempts: 1,
  maxAttempts: 5,
  nextRunAt: '2026-11-20T12:00:00Z',
  lastError: 'Not sent to the ERP: line 2 MISSING_SKU',
  orderNumber: '#1042',
  payload: { orderId: '9af807fc-b819-4448-b7e9-cc6b82ef9ecd' },
  createdAt: '2026-11-20T12:00:00Z',
  updatedAt: '2026-11-20T12:00:00Z',
  attemptHistory: [
    {
      run: 1,
      attempt: 1,
      startedAt: '2026-11-20T12:00:00Z',
      finishedAt: '2026-11-20T12:00:01Z',
      outcome: AttemptOutcome.PermanentFailure,
      error: 'Not sent to the ERP: line 2 MISSING_SKU',
    },
  ],
  lineLog: [
    {
      run: 1,
      attempt: 1,
      lineNumber: 2,
      outcome: LineOutcome.Invalid,
      errorCode: 'MISSING_SKU',
      errorMessage: 'The line has no SKU',
      loggedAt: '2026-11-20T12:00:01Z',
    },
  ],
};

describe('JobDetail', () => {
  async function renderDetail(
    job: GetJobResponse,
    permissions: Permission[],
    retry: (params: RetryJobRequestParams) => Observable<unknown> = () => of({}),
  ) {
    const retries: RetryJobRequestParams[] = [];
    let loads = 0;
    const api = {
      getJob: () => {
        loads++;
        return of(new HttpResponse({ body: job, headers: new HttpHeaders({ ETag: '"3"' }) }));
      },
      retryJob: (params: RetryJobRequestParams) => {
        retries.push(params);
        return retry(params);
      },
    };
    await render(JobDetail, {
      imports: [translocoTesting()],
      providers: [
        provideRouter([]),
        { provide: JobsService, useValue: api },
        { provide: AuthService, useValue: fakeAuth(permissions) },
      ],
      inputs: { id: ID },
    });
    return { retries, loads: () => loads };
  }

  it('shows the job, why it stopped, its attempts and the line log', async () => {
    await renderDetail(deadJob, [Permission.JobsRead]);

    expect(screen.getByRole('heading', { level: 1, name: 'Order #1042' })).toBeTruthy();
    expect(screen.getByText('Stopped after attempt 1: trying again would not help')).toBeTruthy();
    expect(screen.getByText('The error cannot be fixed by trying again')).toBeTruthy();
    expect(screen.getByText('"9af807fc-b819-4448-b7e9-cc6b82ef9ecd"')).toBeTruthy();
    expect(screen.getByRole('table', { name: 'Attempts' })).toBeTruthy();
    expect(screen.getByText('Not sent: invalid')).toBeTruthy();
    expect(screen.getByText('MISSING_SKU')).toBeTruthy();
  });

  it('offers Run again only to users who may retry', async () => {
    await renderDetail(deadJob, [Permission.JobsRead]);

    expect(screen.queryByRole('button', { name: 'Run again' })).toBeNull();
  });

  it('does not offer Run again for a job that succeeded', async () => {
    await renderDetail({ ...deadJob, status: JobStatus.Succeeded, deadReason: null }, [
      Permission.JobsRead,
      Permission.JobsRetry,
    ]);

    expect(screen.queryByRole('button', { name: 'Run again' })).toBeNull();
  });

  it('runs the job again with the version it showed, then reloads it', async () => {
    const user = userEvent.setup();
    const { retries, loads } = await renderDetail(deadJob, [
      Permission.JobsRead,
      Permission.JobsRetry,
    ]);

    await user.click(screen.getByRole('button', { name: 'Run again' }));

    expect(retries).toEqual([{ id: ID, ifMatch: '"3"' }]);
    expect(await screen.findByText('The job will run again shortly.')).toBeTruthy();
    expect(loads()).toBe(2);
  });

  it('explains a conflict in the server’s words and shows the job as it is now', async () => {
    const user = userEvent.setup();
    const conflict = new HttpErrorResponse({
      status: 412,
      error: { code: 'precondition-failed', detail: 'The resource has changed since you read it.' },
    });
    const { loads } = await renderDetail(deadJob, [Permission.JobsRead, Permission.JobsRetry], () =>
      throwError(() => conflict),
    );

    await user.click(screen.getByRole('button', { name: 'Run again' }));

    expect(await screen.findByText('The resource has changed since you read it.')).toBeTruthy();
    expect(loads()).toBe(2);
  });
});
