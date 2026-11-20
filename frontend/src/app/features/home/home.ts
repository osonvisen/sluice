import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { AuthService } from '../../core/auth/auth.service';
import { Permission } from '../../core/auth/permissions';

/** The start page. A logged-in user whose roles grant nothing is told so here, instead of seeing empty screens. */
@Component({
  selector: 'app-home',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoDirective, RouterLink, MatButtonModule],
  template: `
    <ng-container *transloco="let t">
      <h1>{{ t('home.heading') }}</h1>
      <p>{{ t('home.intro') }}</p>
      @if (canReadJobs()) {
        <a mat-flat-button routerLink="/jobs">{{ t('home.openJobs') }}</a>
      } @else if (auth.status() === 'authenticated') {
        <p role="status">{{ t('home.noAccess') }}</p>
      }
    </ng-container>
  `,
})
export class Home {
  protected readonly auth = inject(AuthService);
  protected readonly canReadJobs = () => this.auth.hasPermission(Permission.JobsRead);
}
