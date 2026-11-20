import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { BROWSER_LOCATION } from './browser-location';

/** Spring Security's login entry point for the Keycloak registration (ADR 0004). */
export const LOGIN_PATH = '/oauth2/authorization/keycloak';

const MARKER_KEY = 'sluice.loginRedirectAt';
const LOOP_WINDOW_MS = 30_000;

/**
 * Sends the whole page to the login. The backend answers `/api` with 401 instead of redirecting, so the
 * app decides when to leave (ADR 0004): a full navigation, because the login runs on other pages
 * (Keycloak) and the session cookie is set on the way back.
 *
 * If we were sent back still logged out within a few seconds, logging in is not working (a wrong
 * client secret, cookies blocked): stop and say so, instead of bouncing between the app and Keycloak forever.
 */
@Injectable({ providedIn: 'root' })
export class LoginRedirect {
  private readonly location = inject(BROWSER_LOCATION);
  private readonly storage = inject(DOCUMENT).defaultView?.sessionStorage;

  private readonly loopDetected = signal(false);
  readonly failed = this.loopDetected.asReadonly();

  /** Starts the login, unless that just happened. Returns whether the page is now leaving. */
  redirect(): boolean {
    if (this.loopDetected()) {
      return false;
    }
    const last = Number(this.read() ?? 0);
    if (Date.now() - last < LOOP_WINDOW_MS) {
      this.loopDetected.set(true);
      return false;
    }
    this.write(String(Date.now()));
    this.location.assign(LOGIN_PATH);
    return true;
  }

  /** Called once logged in, so a later expired session can redirect again. */
  succeeded(): void {
    this.write(null);
  }

  // Session storage can throw (private mode, blocked storage); the loop guard is then simply off.
  private read(): string | null {
    try {
      return this.storage?.getItem(MARKER_KEY) ?? null;
    } catch {
      return null;
    }
  }

  private write(value: string | null): void {
    try {
      if (value === null) {
        this.storage?.removeItem(MARKER_KEY);
      } else {
        this.storage?.setItem(MARKER_KEY, value);
      }
    } catch {
      // See read().
    }
  }
}
