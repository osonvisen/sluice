import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { render, screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { Observable, of, throwError } from 'rxjs';
import en from '../../../../public/i18n/en.json';
import nb from '../../../../public/i18n/nb.json';
import { AppInfoResponse, SystemService } from '../api/generated';
import { I18N_CONFIG } from '../i18n/i18n.providers';
import { Shell } from './shell';

describe('Shell', () => {
  function renderShell(appInfo: Observable<AppInfoResponse>) {
    return render(Shell, {
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { en, nb },
          translocoConfig: I18N_CONFIG,
          preloadLangs: true,
        }),
      ],
      providers: [
        provideRouter([]),
        { provide: SystemService, useValue: { getAppInfo: () => appInfo } },
      ],
    });
  }

  it('shows the backend version in the footer', async () => {
    await renderShell(of({ name: 'sluice', version: '1.2.3' }));

    expect(await screen.findByText('Version 1.2.3')).toBeTruthy();
  });

  it('says the version is unavailable when the backend cannot be reached', async () => {
    await renderShell(throwError(() => new HttpErrorResponse({ status: 503 })));

    expect(await screen.findByText('Version unavailable')).toBeTruthy();
  });

  it('switches texts and <html lang> when the user picks another language', async () => {
    const user = userEvent.setup();
    await renderShell(of({ name: 'sluice', version: '1.2.3' }));

    await user.click(screen.getByRole('combobox', { name: 'Language' }));
    await user.click(await screen.findByRole('option', { name: 'Norsk bokmål' }));

    expect(await screen.findByText('Versjon 1.2.3')).toBeTruthy();
    expect(screen.getByRole('combobox', { name: 'Språk' })).toBeTruthy();
    expect(document.documentElement.lang).toBe('nb');
  });

  it('offers a skip link to the main content for keyboard users', async () => {
    await renderShell(of({ name: 'sluice', version: '1.2.3' }));

    expect(screen.getByRole('link', { name: 'Skip to main content' }).getAttribute('href')).toBe(
      '#main',
    );
    expect(screen.getByRole('main')).toBeTruthy();
  });
});
