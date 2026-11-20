import { Routes } from '@angular/router';
import { requirePermission } from './core/auth/require-permission.guard';
import { Permission } from './core/auth/permissions';

export const routes: Routes = [
  {
    path: '',
    title: 'home.title',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  {
    path: 'jobs',
    canActivate: [requirePermission(Permission.JobsRead)],
    loadChildren: () => import('./features/jobs/jobs.routes').then((m) => m.JOBS_ROUTES),
  },
  { path: '**', redirectTo: '' },
];
