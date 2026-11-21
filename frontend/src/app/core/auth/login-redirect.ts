import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { BROWSER_LOCATION } from './browser-location';

/** Spring Security's login entry point for the Keycloak registration (ADR 0004). */
export const LOGIN_PATH = '/oauth2/authorization/keycloak';

const MARKER_KEY = 'sluice.loginRedirectAt';
const RETURN_TO_KEY = 'sluice.returnTo';
const LOOP_WINDOW_MS = 30_000;

/**
 * Sends the whole page to the login. The backend answers `/api` with 401 instead of redirecting, so the
 * app decides when to leave (ADR 0004): a full navigation, because the login runs on other pages
 * (Keycloak) and the session cookie is set on the way back.
 *
 * The page the user was on is remembered and restored after the login ([takeReturnTo]): the backend sends
 * everyone to `/` afterwards, and a shared link to a failed job should open that job.
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
    const last = Number(this.read(MARKER_KEY) ?? 0);
    if (Date.now() - last < LOOP_WINDOW_MS) {
      this.loopDetected.set(true);
      return false;
    }
    this.write(MARKER_KEY, String(Date.now()));
    this.write(RETURN_TO_KEY, this.location.pathname + this.location.search);
    this.location.assign(LOGIN_PATH);
    return true;
  }

  /** Called once logged in, so a later expired session can redirect again. */
  succeeded(): void {
    this.write(MARKER_KEY, null);
  }

  /**
   * The in-app path to go back to after the login, once. Only a path on this site (`/jobs/…`): never
   * `//other.site` or a full URL, so a crafted value cannot send the user elsewhere (open redirect).
   */
  takeReturnTo(): string | null {
    const path = this.read(RETURN_TO_KEY);
    this.write(RETURN_TO_KEY, null);
    return path &&
      path.startsWith('/') &&
      !path.startsWith('//') &&
      !path.startsWith('/\\') &&
      path !== '/'
      ? path
      : null;
  }

  // Session storage can throw (private mode, blocked storage); the loop guard is then simply off.
  private read(key: string): string | null {
    try {
      return this.storage?.getItem(key) ?? null;
    } catch {
      return null;
    }
  }

  private write(key: string, value: string | null): void {
    try {
      if (value === null) {
        this.storage?.removeItem(key);
      } else {
        this.storage?.setItem(key, value);
      }
    } catch {
      // See read().
    }
  }
}
