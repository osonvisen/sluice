import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { provideApi } from './core/api/generated';
import { acceptLanguageInterceptor } from './core/i18n/accept-language.interceptor';
import { provideI18n } from './core/i18n/i18n.providers';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([acceptLanguageInterceptor])),
    // Same origin as the backend (ADR 0005); the generated paths already start with /api.
    provideApi({ basePath: '' }),
    provideI18n(),
  ],
};
