import { HttpErrorResponse } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { render, screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { Observable, of, throwError } from 'rxjs';
import { AppInfoResponse, SystemService } from '../api/generated';
import { AuthService, AuthStatus } from '../auth/auth.service';
import { Permission } from '../auth/permissions';
import { fakeAuth } from '../../testing/fakes';
import { translocoTesting } from '../../testing/i18n';
import { Shell } from './shell';

describe('Shell', () => {
  const version = of({ name: 'sluice', version: '1.2.3' });

  function renderShell(
    appInfo: Observable<AppInfoResponse> = version,
    auth: ReturnType<typeof fakeAuth> = fakeAuth([Permission.JobsRead]),
  ) {
    return render(Shell, {
      imports: [translocoTesting()],
      providers: [
        provideRouter([]),
        { provide: SystemService, useValue: { getAppInfo: () => appInfo } },
        { provide: AuthService, useValue: auth },
      ],
    });
  }

  it('shows the backend version in the footer', async () => {
    await renderShell();

    expect(await screen.findByText('Version 1.2.3')).toBeTruthy();
  });

  it('says the version is unavailable when the backend cannot be reached', async () => {
    await renderShell(throwError(() => new HttpErrorResponse({ status: 503 })));

    expect(await screen.findByText('Version unavailable')).toBeTruthy();
  });

  it('switches texts and <html lang>, and saves the choice on the user', async () => {
    const user = userEvent.setup();
    const auth = fakeAuth([Permission.JobsRead]);
    const save = vi.spyOn(auth, 'saveLanguage');
    await renderShell(version, auth);

    await user.click(screen.getByRole('combobox', { name: 'Language' }));
    await user.click(await screen.findByRole('option', { name: 'Norsk bokmål' }));

    expect(await screen.findByText('Versjon 1.2.3')).toBeTruthy();
    expect(screen.getByRole('combobox', { name: 'Språk' })).toBeTruthy();
    expect(document.documentElement.lang).toBe('nb');
    expect(save).toHaveBeenCalledWith('nb');
  });

  it('says so when the language could not be saved', async () => {
    const user = userEvent.setup();
    const auth = { ...fakeAuth([]), saveLanguage: () => throwError(() => new Error('down')) };
    await renderShell(version, auth);

    await user.click(screen.getByRole('combobox', { name: 'Language' }));
    await user.click(await screen.findByRole('option', { name: 'Norsk bokmål' }));

    expect(await screen.findByText(/Språket kunne ikke lagres/)).toBeTruthy();
  });

  it('shows the user and a logout button, and the job link only with permission', async () => {
    await renderShell(version, fakeAuth([Permission.JobsRead]));

    expect(screen.getByText('Olivia Operator')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Log out' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Jobs' })).toBeTruthy();
  });

  it('hides the job link from a user without permissions', async () => {
    await renderShell(version, fakeAuth([]));

    expect(screen.queryByRole('link', { name: 'Jobs' })).toBeNull();
  });

  it.each<[AuthStatus, string]>([
    ['loginFailed', 'Could not log you in'],
    ['unavailable', 'Sluice cannot be reached'],
  ])(
    'when the user could not be loaded (%s), it says why instead of showing screens',
    async (status, text) => {
      await renderShell(version, fakeAuth([], status));

      expect(screen.getByRole('alert').textContent).toContain(text);
    },
  );

  it('offers a skip link to the main content for keyboard users', async () => {
    await renderShell();

    expect(screen.getByRole('link', { name: 'Skip to main content' }).getAttribute('href')).toBe(
      '#main',
    );
    expect(screen.getByRole('main')).toBeTruthy();
  });
});
