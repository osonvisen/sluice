import { HttpErrorResponse } from '@angular/common/http';

/**
 * An API error as RFC 9457 ProblemDetail. The backend already translated `title` and `detail` into the
 * language we sent in `Accept-Language`, so they can be shown as they are; `code` is for logic.
 */
export interface Problem {
  status: number;
  code?: string;
  title?: string;
  detail?: string;
  correlationId?: string;
}

/** Status 0: the request never got an answer (offline, backend down, CORS). */
export const NETWORK_ERROR_STATUS = 0;

/** Accepts an HttpErrorResponse, or an error wrapping one in `cause` (as Angular's resources do). */
export function toProblem(error: unknown): Problem {
  if (
    error instanceof Error &&
    error.cause !== undefined &&
    !(error instanceof HttpErrorResponse)
  ) {
    return toProblem(error.cause);
  }
  if (!(error instanceof HttpErrorResponse)) {
    return { status: NETWORK_ERROR_STATUS };
  }
  const body: unknown = error.error;
  if (typeof body !== 'object' || body === null) {
    return { status: error.status };
  }
  const field = (name: string): string | undefined => {
    const value = (body as Record<string, unknown>)[name];
    return typeof value === 'string' ? value : undefined;
  };
  return {
    status: error.status,
    code: field('code'),
    title: field('title'),
    detail: field('detail'),
    correlationId: field('correlationId'),
  };
}
