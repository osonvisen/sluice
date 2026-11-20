import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { Router, RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { map } from 'rxjs';
import { JobListItem, JobStatus, JobsService } from '../../../core/api/generated';
import { LanguageService } from '../../../core/i18n/language.service';
import { DataTable } from '../../../shared/data-table/data-table';
import { DateTimePipe } from '../../../shared/format/date-time';
import { injectNow } from '../../../shared/format/now';
import { toProblem } from '../../../shared/problem/problem';
import { StatusChip } from '../../../shared/status-chip/status-chip';
import { JOB_STATUSES, describeAttempts, isJobStatus, statusTone } from '../job-presentation';

const DEFAULT_PAGE_SIZE = 20;
const PAGE_SIZES = [10, 20, 50, 100] as const;
const WIDE_COLUMNS = ['status', 'job', 'progress', 'lastError', 'created'] as const;
const NARROW_COLUMNS = ['status', 'job', 'progress'] as const;

interface Filter {
  status?: JobStatus;
  q?: string;
  page: number;
  size: number;
}

/**
 * The job overview. The filter lives in the URL (`?status=DEAD&q=1042&page=1`), so a filtered list can be
 * bookmarked or shared, and the back button works. Router query params arrive as inputs
 * (`withComponentInputBinding`); anything invalid in them falls back to the default.
 */
@Component({
  selector: 'app-job-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoDirective,
    RouterLink,
    MatTableModule,
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
    MatButtonModule,
    DataTable,
    StatusChip,
    DateTimePipe,
  ],
  templateUrl: './job-list.html',
  styleUrl: './job-list.scss',
})
export class JobList {
  readonly status = input<string>();
  readonly q = input<string>();
  readonly page = input<string>();
  readonly size = input<string>();

  private readonly jobsApi = inject(JobsService);
  private readonly router = inject(Router);

  protected readonly language = inject(LanguageService).current;
  protected readonly now = injectNow();
  protected readonly statuses = JOB_STATUSES;
  protected readonly pageSizes = PAGE_SIZES;
  protected readonly statusTone = statusTone;

  protected readonly filter = computed<Filter>(() => ({
    status: isJobStatus(this.status()) ? (this.status() as JobStatus) : undefined,
    q: this.q()?.trim() || undefined,
    page: Math.max(0, toInt(this.page()) ?? 0),
    size: PAGE_SIZES.find((size) => size === toInt(this.size())) ?? DEFAULT_PAGE_SIZE,
  }));

  protected readonly jobs = rxResource({
    params: () => this.filter(),
    stream: ({ params }) => this.jobsApi.listJobs(params),
  });

  protected readonly problem = computed(() =>
    this.jobs.error() ? toProblem(this.jobs.error()) : null,
  );

  private readonly narrow = toSignal(
    inject(BreakpointObserver)
      .observe([Breakpoints.XSmall])
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );
  protected readonly columns = computed(() => (this.narrow() ? NARROW_COLUMNS : WIDE_COLUMNS));

  protected progress(job: JobListItem) {
    return describeAttempts(job, this.now(), this.language());
  }

  protected filterByStatus(status: JobStatus | undefined): void {
    this.navigate({ status, page: undefined });
  }

  protected search(text: string): void {
    this.navigate({ q: text.trim() || undefined, page: undefined });
  }

  protected changePage(event: PageEvent): void {
    this.navigate({
      page: event.pageIndex || undefined,
      size: event.pageSize === DEFAULT_PAGE_SIZE ? undefined : event.pageSize,
    });
  }

  /** `undefined` removes the parameter, so the URL only shows what differs from the default. */
  private navigate(changes: Partial<Record<keyof Filter, string | number | undefined>>): void {
    void this.router.navigate([], { queryParams: changes, queryParamsHandling: 'merge' });
  }
}

function toInt(value: string | undefined): number | undefined {
  const parsed = Number.parseInt(value ?? '', 10);
  return Number.isNaN(parsed) ? undefined : parsed;
}
