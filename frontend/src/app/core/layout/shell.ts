import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterOutlet } from '@angular/router';
import { TranslocoDirective } from '@jsverse/transloco';
import { catchError, of } from 'rxjs';
import { AppInfoResponse, SystemService } from '../api/generated';
import { LANGUAGES, Language } from '../i18n/languages';
import { LanguageService } from '../i18n/language.service';

/** The app frame: toolbar with language switcher, routed content and a footer with the build version. */
@Component({
  selector: 'app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    TranslocoDirective,
    MatToolbarModule,
    MatFormFieldModule,
    MatSelectModule,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly languageService = inject(LanguageService);

  protected readonly languages = LANGUAGES;
  protected readonly language = this.languageService.current;

  /** `undefined` while loading, `null` if the backend could not be reached. */
  protected readonly appInfo = toSignal<AppInfoResponse | null>(
    inject(SystemService)
      .getAppInfo()
      .pipe(catchError(() => of(null))),
  );

  protected changeLanguage(language: Language): void {
    this.languageService.use(language);
  }
}
