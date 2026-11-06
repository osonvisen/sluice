import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { Language, resolveInitialLanguage } from './languages';

const STORAGE_KEY = 'sluice.language';

/**
 * The active UI language. The choice is remembered in the browser for now; it moves to the
 * user profile once login exists (milestone 3).
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
