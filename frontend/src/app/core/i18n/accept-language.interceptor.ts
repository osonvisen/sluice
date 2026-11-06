import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { LanguageService } from './language.service';

/** Sends the UI language to the backend so error messages (ProblemDetail) come back in the same language. */
export const acceptLanguageInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith('/api/')) {
    return next(request);
  }
  const language = inject(LanguageService).current();
  return next(request.clone({ setHeaders: { 'Accept-Language': language } }));
};
