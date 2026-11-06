import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslocoDirective } from '@jsverse/transloco';

@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective],
  template: `
    <ng-container *transloco="let t">
      <h1>{{ t('home.heading') }}</h1>
      <p>{{ t('home.intro') }}</p>
    </ng-container>
  `,
})
export class Home {}
