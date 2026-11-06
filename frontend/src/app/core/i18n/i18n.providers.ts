import { EnvironmentProviders, inject, isDevMode, provideAppInitializer } from '@angular/core';
import { provideTransloco, translocoConfig } from '@jsverse/transloco';
import { DEFAULT_LANGUAGE, LANGUAGES } from './languages';
import { LanguageService } from './language.service';
import { TranslocoHttpLoader } from './transloco-http-loader';

/** Shared by the app and the tests, so tests run with the same behaviour as production. */
export const I18N_CONFIG = translocoConfig({
  availableLangs: [...LANGUAGES],
  defaultLang: DEFAULT_LANGUAGE,
  fallbackLang: DEFAULT_LANGUAGE,
  missingHandler: { useFallbackTranslation: true, logMissingKey: isDevMode() },
  reRenderOnLangChange: true,
  prodMode: !isDevMode(),
});

export function provideI18n(): EnvironmentProviders[] {
  return [
    ...provideTransloco({ config: I18N_CONFIG, loader: TranslocoHttpLoader }),
    provideAppInitializer(() => inject(LanguageService).init()),
  ];
}
