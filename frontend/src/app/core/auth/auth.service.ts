import { HttpClient, HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { MeResponse, MeService } from '../api/generated';
import { Language } from '../i18n/languages';
import { LanguageService } from '../i18n/language.service';
import { BROWSER_LOCATION } from './browser-location';
import { LoginRedirect } from './login-redirect';
import { Permission } from './permissions';

export type AuthStatus = 'loading' | 'authenticated' | 'unavailable' | 'loginFailed';

/**
 * Who is logged in, from `GET /api/me` (ADR 0004, 0011). The session itself lives in Spring Boot (BFF):
 * the app never sees a token, only this profile and the permissions that decide what it shows.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly meApi = inject(MeService);
  private readonly http = inject(HttpClient);
  private readonly language = inject(LanguageService);
  private readonly loginRedirect = inject(LoginRedirect);
  private readonly location = inject(BROWSER_LOCATION);

  private readonly state = signal<{ status: AuthStatus; me: MeResponse | null }>({
    status: 'loading',
    me: null,
  });

  readonly status = computed(() => this.state().status);
  readonly me = computed(() => this.state().me);

  /**
   * Runs before the first screen (app initializer). On a 401 the interceptor has already sent the page
   * to the login, so this never resolves: nothing should render on a page that is leaving.
   */
  async load(): Promise<void> {
    try {
      const me = await firstValueFrom(this.meApi.getMe());
      this.loginRedirect.succeeded();
      this.state.set({ status: 'authenticated', me });
      this.language.applyUserChoice(me.language);
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === HttpStatusCode.Unauthorized) {
        if (this.loginRedirect.failed()) {
          this.state.set({ status: 'loginFailed', me: null });
          return;
        }
        return new Promise<void>(() => undefined);
      }
      this.state.set({ status: 'unavailable', me: null });
    }
  }

  hasPermission(permission: Permission): boolean {
    return this.me()?.permissions.includes(permission) ?? false;
  }

  /** Stores the language on the user (ADR 0011). Logged-out visitors keep it in the browser only. */
  saveLanguage(language: Language): Observable<unknown> | null {
    if (this.status() !== 'authenticated') {
      return null;
    }
    return this.meApi.changeLanguage({ changeLanguageRequest: { language } });
  }

  /**
   * Ends the session in Spring Boot and, with Keycloak, in Keycloak too: the backend answers 202 with
   * Keycloak's logout URL in `Location` (an XHR cannot follow that redirect itself). Spring Security's
   * `/logout` is not part of the API, hence a plain HttpClient call instead of the generated client.
   * Without an identity provider (local-noauth) there is no URL, and a reload is enough.
   */
  logout(): void {
    this.http.post('/logout', null, { observe: 'response' }).subscribe({
      next: (response) => {
        const target = response.headers.get('Location');
        if (target) {
          this.location.assign(target);
        } else {
          this.location.reload();
        }
      },
      // A failed logout (e.g. the session had already expired) still leaves the user logged out.
      error: () => this.location.reload(),
    });
  }
}
