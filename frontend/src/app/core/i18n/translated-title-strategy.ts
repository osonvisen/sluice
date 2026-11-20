import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';

/**
 * Gives every screen its own browser title, "Jobs – Sluice" (WCAG 2.4.2 Page Titled): screen readers
 * announce it on navigation, and it names the tab. Routes set a Transloco key as `title`; the title is
 * translated, and translated again when the language changes.
 */
@Injectable()
export class TranslatedTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly transloco = inject(TranslocoService);
  private lastKey: string | undefined;

  constructor() {
    super();
    this.transloco.selectTranslation().subscribe(() => this.apply());
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.lastKey = this.buildTitle(snapshot);
    this.apply();
  }

  private apply(): void {
    const app = this.transloco.translate('app.title');
    this.title.setTitle(this.lastKey ? `${this.transloco.translate(this.lastKey)} – ${app}` : app);
  }
}
