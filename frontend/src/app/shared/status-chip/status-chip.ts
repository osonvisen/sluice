import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** What a status means to the reader. The colours come from design tokens in styles.scss. */
export type StatusTone = 'success' | 'info' | 'warning' | 'error' | 'neutral';

/**
 * A small coloured label for a status. The text always says what the colour means, so colour is never
 * the only carrier of information (WCAG 1.4.1). Not interactive.
 */
@Component({
  selector: 'app-status-chip',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="'chip tone-' + tone()">{{ label() }}</span>`,
  styleUrl: './status-chip.scss',
})
export class StatusChip {
  readonly label = input.required<string>();
  readonly tone = input<StatusTone>('neutral');
}
