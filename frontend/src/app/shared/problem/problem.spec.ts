import { HttpErrorResponse } from '@angular/common/http';
import { toProblem } from './problem';

describe('toProblem', () => {
  it('reads code, translated text and correlation id from a problem detail', () => {
    const error = new HttpErrorResponse({
      status: 409,
      error: {
        code: 'job-not-retryable',
        title: 'The job cannot be run again',
        detail: 'Only failed or dead jobs can be run again.',
        correlationId: 'abc-123',
      },
    });

    expect(toProblem(error)).toEqual({
      status: 409,
      code: 'job-not-retryable',
      title: 'The job cannot be run again',
      detail: 'Only failed or dead jobs can be run again.',
      correlationId: 'abc-123',
    });
  });

  it('keeps only the status when the body is not a problem', () => {
    expect(toProblem(new HttpErrorResponse({ status: 502, error: 'Bad gateway' }))).toEqual({
      status: 502,
    });
  });

  it('unwraps an error that carries the http error as its cause', () => {
    const wrapped = new Error('resource failed', {
      cause: new HttpErrorResponse({ status: 404, error: { code: 'not-found' } }),
    });

    expect(toProblem(wrapped)).toEqual({ status: 404, code: 'not-found' });
  });

  it('treats anything else as a network error', () => {
    expect(toProblem(new Error('boom')).status).toBe(0);
  });
});
