import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { TranslocoDirective } from '@jsverse/transloco';
import { NETWORK_ERROR_STATUS, Problem } from '../problem/problem';

/**
 * Shown when loading failed. Uses the backend's translated title and detail when there are any, and the
 * correlation ID, so support can find the request in the logs.
 */
@Component({
  selector: 'app-error-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, MatButtonModule],
  templateUrl: './error-state.html',
  styleUrl: './error-state.scss',
})
export class ErrorState {
  readonly problem = input.required<Problem>();
  /** Offer "Try again" only where it makes sense. */
  readonly retryable = input(true);
  readonly retry = output<void>();

  protected readonly networkError = NETWORK_ERROR_STATUS;
}
