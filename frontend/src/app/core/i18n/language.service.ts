import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { Language, isLanguage, resolveInitialLanguage } from './languages';

const STORAGE_KEY = 'sluice.language';

/**
 * The active UI language (ADR 0006, ADR 0011).
 *
 * At startup: the choice remembered in this browser, then the browser language, then English. Once
 * logged in, the choice stored on the user wins ([applyUserChoice]), so it follows the person to other
 * devices. Saving a new choice on the server is `AuthService.saveLanguage`; the browser copy stays as
 * the fallback for logged-out visitors.
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);
  private readonly document = inject(DOCUMENT);

  private readonly active = signal<Language>(this.transloco.getDefaultLang() as Language);
  readonly current = this.active.asReadonly();

  init(): void {
    const browserLanguages = this.document.defaultView?.navigator.languages ?? [];
    this.apply(resolveInitialLanguage(this.readStored(), browserLanguages));
  }

  use(language: Language): void {
    this.apply(language);
    this.store(language);
  }

  /** The language stored on the logged-in user, if they chose one. Anything unknown is ignored. */
  applyUserChoice(language: string | null | undefined): void {
    if (isLanguage(language)) {
      this.use(language);
    }
  }

  private apply(language: Language): void {
    this.transloco.setActiveLang(language);
    // Screen readers use <html lang> to pick pronunciation.
    this.document.documentElement.lang = language;
    this.active.set(language);
  }

  // Storage can throw (private mode, blocked cookies); the language then just isn't remembered.
  private readStored(): string | null {
    try {
      return this.document.defaultView?.localStorage.getItem(STORAGE_KEY) ?? null;
    } catch {
      return null;
    }
  }

  private store(language: Language): void {
    try {
      this.document.defaultView?.localStorage.setItem(STORAGE_KEY, language);
    } catch {
      // Not remembering the choice is acceptable.
    }
  }
}
