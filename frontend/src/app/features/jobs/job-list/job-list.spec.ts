import { provideRouter, Router } from '@angular/router';
import { render, screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { Observable, of } from 'rxjs';
import {
  JobListItem,
  JobStatus,
  JobsService,
  ListJobsRequestParams,
  PageJobListItem,
} from '../../../core/api/generated';
import { translocoTesting } from '../../../testing/i18n';
import { JobList } from './job-list';

const job = (overrides: Partial<JobListItem>): JobListItem => ({
  id: '11111111-1111-1111-1111-111111111111',
  type: 'order.import',
  status: JobStatus.Succeeded,
  attempts: 1,
  maxAttempts: 5,
  nextRunAt: '2026-11-20T12:00:00Z',
  createdAt: '2026-11-20T12:00:00Z',
  updatedAt: '2026-11-20T12:00:00Z',
  orderNumber: '#1042',
  ...overrides,
});

describe('JobList', () => {
  async function renderList(inputs: Record<string, string> = {}, page?: PageJobListItem) {
    const calls: ListJobsRequestParams[] = [];
    const api = {
      listJobs: (params: ListJobsRequestParams): Observable<PageJobListItem> => {
        calls.push(params);
        return of(page ?? { items: [job({})], page: 0, size: 20, total: 1 });
      },
    };
    const result = await render(JobList, {
      imports: [translocoTesting()],
      providers: [provideRouter([]), { provide: JobsService, useValue: api }],
      inputs,
    });
    return { ...result, calls };
  }

  it('lists jobs with a status the reader understands and a link to the detail', async () => {
    await renderList();

    const table = screen.getByRole('table', { name: 'Jobs' });
    expect(within(table).getByText('Succeeded')).toBeTruthy();
    expect(within(table).getByText('Succeeded on attempt 1 of 5')).toBeTruthy();
    expect(within(table).getByRole('link', { name: 'Order #1042' }).getAttribute('href')).toBe(
      '/11111111-1111-1111-1111-111111111111',
    );
  });

  it('asks the api with the filter from the url', async () => {
    const { calls } = await renderList({ status: 'DEAD', q: ' 1042 ', page: '2', size: '50' });

    expect(calls.at(-1)).toEqual({ status: 'DEAD', q: '1042', page: 2, size: 50 });
  });

  it('falls back to the defaults for invalid values in the url', async () => {
    const { calls } = await renderList({ status: 'SLEEPING', page: '-3', size: '7' });

    expect(calls.at(-1)).toEqual({ status: undefined, q: undefined, page: 0, size: 20 });
  });

  it('puts a search in the url and starts from the first page', async () => {
    const user = userEvent.setup();
    const { fixture } = await renderList({ page: '3' });
    const router = fixture.debugElement.injector.get(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    await user.type(screen.getByRole('searchbox', { name: 'Order number or job ID' }), '#1042');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(navigate).toHaveBeenCalledWith([], {
      queryParams: { q: '#1042', page: undefined },
      queryParamsHandling: 'merge',
    });
  });

  it('says when there are no jobs at all', async () => {
    await renderList({}, { items: [], page: 0, size: 20, total: 0 });

    expect(screen.getByText('There are no jobs yet.')).toBeTruthy();
  });

  it('says when the filter matches nothing', async () => {
    await renderList({ q: 'nope' }, { items: [], page: 0, size: 20, total: 0 });

    expect(screen.getByText('No jobs match the filter.')).toBeTruthy();
  });
});
