import { isLanguage, resolveInitialLanguage } from './languages';

describe('resolveInitialLanguage', () => {
  it('uses the stored choice first', () => {
    expect(resolveInitialLanguage('nb', ['en-US'])).toBe('nb');
  });

  it('ignores a stored value that is not a supported language', () => {
    expect(resolveInitialLanguage('de', ['nb-NO'])).toBe('nb');
  });

  it('uses the first supported browser language', () => {
    expect(resolveInitialLanguage(null, ['de-DE', 'en-GB', 'nb'])).toBe('en');
  });

  it.each(['no', 'nn-NO', 'NB-no'])('treats %s as Norwegian Bokmål', (tag) => {
    expect(resolveInitialLanguage(null, [tag])).toBe('nb');
  });

  it('falls back to English', () => {
    expect(resolveInitialLanguage(null, ['de-DE', 'sv-SE'])).toBe('en');
    expect(resolveInitialLanguage(null, [])).toBe('en');
  });
});

describe('isLanguage', () => {
  it('accepts only supported languages', () => {
    expect(isLanguage('en')).toBe(true);
    expect(isLanguage('nb')).toBe(true);
    expect(isLanguage('no')).toBe(false);
    expect(isLanguage(null)).toBe(false);
  });
});
