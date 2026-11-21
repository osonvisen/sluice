import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { TitleStrategy, provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { provideApi } from './core/api/generated';
import { AuthService } from './core/auth/auth.service';
import { unauthorizedInterceptor } from './core/auth/unauthorized.interceptor';
import { acceptLanguageInterceptor } from './core/i18n/accept-language.interceptor';
import { provideI18n } from './core/i18n/i18n.providers';
import { TranslatedTitleStrategy } from './core/i18n/translated-title-strategy';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    { provide: TitleStrategy, useClass: TranslatedTitleStrategy },
    // Angular's HttpClient sends the XSRF-TOKEN cookie back as X-XSRF-TOKEN on same-origin POST/PUT/DELETE
    // by default, which is what the backend checks (ADR 0004). No configuration needed.
    provideHttpClient(
      withFetch(),
      withInterceptors([acceptLanguageInterceptor, unauthorizedInterceptor]),
    ),
    // Same origin as the backend (ADR 0005); the generated paths already start with /api.
    provideApi({ basePath: '' }),
    provideI18n(),
    // After the language initializer (provideI18n): the user's stored language then overrides the browser's.
    provideAppInitializer(() => inject(AuthService).load()),
  ],
};
