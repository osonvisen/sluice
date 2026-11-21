import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Shown instead of a list with no rows. The text is already translated by the caller. */
@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<p class="empty" role="status">{{ message() }}</p>`,
  styles: `
    .empty {
      margin: 0;
      padding: var(--sluice-space-lg);
      text-align: center;
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class EmptyState {
  readonly message = input.required<string>();
}
