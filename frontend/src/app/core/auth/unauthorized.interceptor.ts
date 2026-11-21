import { HttpErrorResponse, HttpInterceptorFn, HttpStatusCode } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { LoginRedirect } from './login-redirect';

/**
 * A 401 from the API means the session is gone (never logged in, expired, or logged out in another tab):
 * send the whole page to the login. The error still reaches the caller, so nothing renders stale data.
 */
export const unauthorizedInterceptor: HttpInterceptorFn = (request, next) => {
  const loginRedirect = inject(LoginRedirect);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === HttpStatusCode.Unauthorized &&
        request.url.startsWith('/api/')
      ) {
        loginRedirect.redirect();
      }
      return throwError(() => error);
    }),
  );
};
