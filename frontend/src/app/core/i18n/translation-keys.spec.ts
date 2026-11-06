import en from '../../../../public/i18n/en.json';
import nb from '../../../../public/i18n/nb.json';
import { LANGUAGES } from './languages';

interface TranslationTree {
  [key: string]: string | TranslationTree;
}

function flatten(tree: TranslationTree, prefix = ''): Map<string, string> {
  const result = new Map<string, string>();
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') {
      result.set(path, value);
    } else {
      flatten(value, path).forEach((v, k) => result.set(k, v));
    }
  }
  return result;
}

// Every supported language must have a file here; the first test catches a forgotten one.
const translations: Record<string, TranslationTree> = { en, nb };

describe('translation files', () => {
  it('exist for every supported language', () => {
    expect(Object.keys(translations).sort()).toEqual([...LANGUAGES].sort());
  });

  it.each(LANGUAGES.filter((language) => language !== 'en'))(
    '%s has exactly the same keys as en',
    (language) => {
      const expected = [...flatten(en).keys()].sort();
      const actual = [...flatten(translations[language]).keys()].sort();
      expect(actual).toEqual(expected);
    },
  );

  it.each(LANGUAGES)('%s has no empty texts', (language) => {
    const empty = [...flatten(translations[language]).entries()]
      .filter(([, text]) => text.trim() === '')
      .map(([key]) => key);
    expect(empty).toEqual([]);
  });
});
