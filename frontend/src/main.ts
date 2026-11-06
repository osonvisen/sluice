import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { Shell } from './app/core/layout/shell';

bootstrapApplication(Shell, appConfig).catch((err) => console.error(err));
