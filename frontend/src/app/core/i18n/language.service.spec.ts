import { TestBed } from '@angular/core/testing';
import { translocoTesting } from '../../testing/i18n';
import { LanguageService } from './language.service';

describe('LanguageService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ imports: [translocoTesting()] });
  });

  it('the language stored on the user wins, and is remembered in this browser', () => {
    const languages = TestBed.inject(LanguageService);

    languages.applyUserChoice('nb');

    expect(languages.current()).toBe('nb');
    expect(localStorage.getItem('sluice.language')).toBe('nb');
  });

  it('keeps the current language when the user has not chosen one, or the value is unknown', () => {
    const languages = TestBed.inject(LanguageService);
    languages.use('en');

    languages.applyUserChoice(null);
    languages.applyUserChoice('sv');

    expect(languages.current()).toBe('en');
  });
});
