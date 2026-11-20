import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { EMPTY, catchError, of } from 'rxjs';
import { AppInfoResponse, SystemService } from '../api/generated';
import { AuthService } from '../auth/auth.service';
import { BROWSER_LOCATION } from '../auth/browser-location';
import { Permission } from '../auth/permissions';
import { LANGUAGES, Language } from '../i18n/languages';
import { LanguageService } from '../i18n/language.service';
import { ErrorState } from '../../shared/error-state/error-state';

/**
 * The app frame: toolbar with navigation, language switcher and the logged-in user, routed content, and a
 * footer with the build version. If the user could not be loaded, it says why instead of showing screens
 * that would only fail.
 */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    TranslocoDirective,
    MatToolbarModule,
    MatFormFieldModule,
    MatSelectModule,
    MatButtonModule,
    ErrorState,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly languageService = inject(LanguageService);
  private readonly location = inject(BROWSER_LOCATION);
  protected readonly auth = inject(AuthService);

  protected readonly languages = LANGUAGES;
  protected readonly language = this.languageService.current;
  /** Shown under the toolbar; a snack bar here would pull the overlay code into the first bundle. */
  protected readonly languageSaveFailed = signal(false);
  protected readonly canReadJobs = computed(() => this.auth.hasPermission(Permission.JobsRead));

  /** `undefined` while loading, `null` if the backend could not be reached. */
  protected readonly appInfo = toSignal<AppInfoResponse | null>(
    inject(SystemService)
      .getAppInfo()
      .pipe(catchError(() => of(null))),
  );

  protected changeLanguage(language: Language): void {
    this.languageService.use(language);
    this.languageSaveFailed.set(false);
    this.auth
      .saveLanguage(language)
      ?.pipe(
        catchError(() => {
          // The language is still switched here; it just won't follow the user to another device.
          this.languageSaveFailed.set(true);
          return EMPTY;
        }),
      )
      .subscribe();
  }

  protected logout(): void {
    this.auth.logout();
  }

  protected reload(): void {
    this.location.reload();
  }
}
