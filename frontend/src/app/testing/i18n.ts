import { TranslocoTestingModule } from '@jsverse/transloco';
import en from '../../../public/i18n/en.json';
import nb from '../../../public/i18n/nb.json';
import { I18N_CONFIG } from '../core/i18n/i18n.providers';

/** The real translation files, loaded up front, with the app's Transloco configuration. */
export function translocoTesting() {
  return TranslocoTestingModule.forRoot({
    langs: { en, nb },
    translocoConfig: I18N_CONFIG,
    preloadLangs: true,
  });
}
