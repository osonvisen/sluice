import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { RouterLink } from '@angular/router';
import { TranslocoDirective, TranslocoService } from '@jsverse/transloco';
import { JobsService } from '../../../core/api/generated';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permissions';
import { LanguageService } from '../../../core/i18n/language.service';
import { DataTable } from '../../../shared/data-table/data-table';
import { DateTimePipe } from '../../../shared/format/date-time';
import { injectNow } from '../../../shared/format/now';
import { ErrorState } from '../../../shared/error-state/error-state';
import { toProblem } from '../../../shared/problem/problem';
import { StatusChip, StatusTone } from '../../../shared/status-chip/status-chip';
import { canRunAgain, describeAttempts, statusTone } from '../job-presentation';

const OUTCOME_TONES: Readonly<Record<string, StatusTone>> = {
  SUCCEEDED: 'success',
  ACCEPTED: 'success',
  TEMPORARY_FAILURE: 'warning',
  LEASE_EXPIRED: 'warning',
  NO_RESPONSE: 'warning',
  PERMANENT_FAILURE: 'error',
  REJECTED: 'error',
  ORDER_REJECTED: 'error',
  INVALID: 'error',
};

/**
 * One job: what it works on, every attempt, and what was sent to the ERP per order line, with "Run again"
 * for failed and dead jobs. The job's version comes back as an ETag and goes into `If-Match`, so a rerun
 * never acts on a job someone else changed in the meantime (412).
 */
@Component({
  selector: 'app-job-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoDirective,
    RouterLink,
    MatButtonModule,
    MatTableModule,
    DataTable,
    StatusChip,
    ErrorState,
    DateTimePipe,
  ],
  templateUrl: './job-detail.html',
  styleUrl: './job-detail.scss',
})
export class JobDetail {
  /** From the route (`/jobs/:id`). */
  readonly id = input.required<string>();

  private readonly jobsApi = inject(JobsService);
  private readonly auth = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly transloco = inject(TranslocoService);

  protected readonly language = inject(LanguageService).current;
  protected readonly now = injectNow();
  protected readonly statusTone = statusTone;
  protected readonly attemptColumns = ['run', 'attempt', 'started', 'finished', 'outcome', 'error'];
  protected readonly lineColumns = [
    'run',
    'attempt',
    'line',
    'outcome',
    'sent',
    'received',
    'error',
  ];

  private readonly response = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.jobsApi.getJob({ id: params }, 'response'),
  });

  protected readonly job = computed(() => this.response.value()?.body ?? null);
  protected readonly loading = this.response.isLoading;
  protected readonly problem = computed(() =>
    this.response.error() ? toProblem(this.response.error()) : null,
  );
  private readonly etag = computed(() => this.response.value()?.headers.get('ETag') ?? undefined);

  protected readonly progress = computed(() => {
    const job = this.job();
    return job ? describeAttempts(job, this.now(), this.language()) : null;
  });
  protected readonly payload = computed(() => Object.entries(this.job()?.payload ?? {}));
  protected readonly showRunAgain = computed(() => {
    const job = this.job();
    return job !== null && canRunAgain(job.status) && this.auth.hasPermission(Permission.JobsRetry);
  });
  protected readonly retrying = signal(false);

  protected outcomeTone(outcome: string | null | undefined): StatusTone {
    return (outcome && OUTCOME_TONES[outcome]) || 'neutral';
  }

  protected json(value: unknown): string {
    return value === null || value === undefined ? '' : JSON.stringify(value);
  }

  protected reload(): void {
    this.response.reload();
  }

  protected runAgain(): void {
    this.retrying.set(true);
    this.jobsApi.retryJob({ id: this.id(), ifMatch: this.etag() }).subscribe({
      next: () => {
        this.retrying.set(false);
        this.snackBar.open(this.transloco.translate('jobs.detail.runAgainDone'), undefined, {
          duration: 5000,
        });
        this.reload();
      },
      error: (error: unknown) => {
        this.retrying.set(false);
        const problem = toProblem(error);
        this.snackBar.open(
          problem.detail ?? problem.title ?? this.transloco.translate('errors.generic.detail'),
          this.transloco.translate('errors.dismiss'),
        );
        // 412 (someone else changed it) or 409 (no longer failed): show what the job looks like now.
        if (
          error instanceof HttpErrorResponse &&
          (error.status === HttpStatusCode.PreconditionFailed ||
            error.status === HttpStatusCode.Conflict)
        ) {
          this.reload();
        }
      },
    });
  }
}
