/** Languages the UI is translated into (ADR 0006). Adding one means adding `public/i18n/<lang>.json`. */
export const LANGUAGES = ['en', 'nb'] as const;

export type Language = (typeof LANGUAGES)[number];

export const DEFAULT_LANGUAGE: Language = 'en';

export function isLanguage(value: string | null | undefined): value is Language {
  return LANGUAGES.some((language) => language === value);
}

/** Browsers report Norwegian as `nb`, `no` or `nn`. We only have Bokmål, which Nynorsk readers also understand. */
const ALIASES: Readonly<Record<string, Language>> = { no: 'nb', nn: 'nb' };

/**
 * Picks the starting language: the user's stored choice, then the first supported browser
 * language, then English.
 */
export function resolveInitialLanguage(
  stored: string | null,
  browserLanguages: readonly string[],
): Language {
  if (isLanguage(stored)) {
    return stored;
  }
  for (const tag of browserLanguages) {
    const primary = tag.toLowerCase().split('-')[0];
    const language = ALIASES[primary] ?? primary;
    if (isLanguage(language)) {
      return language;
    }
  }
  return DEFAULT_LANGUAGE;
}
