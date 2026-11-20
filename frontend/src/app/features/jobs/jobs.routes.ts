import { Routes } from '@angular/router';

export const JOBS_ROUTES: Routes = [
  {
    path: '',
    title: 'jobs.list.heading',
    loadComponent: () => import('./job-list/job-list').then((m) => m.JobList),
  },
  {
    path: ':id',
    title: 'jobs.detail.title',
    loadComponent: () => import('./job-detail/job-detail').then((m) => m.JobDetail),
  },
];
