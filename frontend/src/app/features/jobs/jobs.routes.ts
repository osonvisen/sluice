import { Routes } from '@angular/router';

export const JOBS_ROUTES: Routes = [
  { path: '', loadComponent: () => import('./job-list/job-list').then((m) => m.JobList) },
  { path: ':id', loadComponent: () => import('./job-detail/job-detail').then((m) => m.JobDetail) },
];
