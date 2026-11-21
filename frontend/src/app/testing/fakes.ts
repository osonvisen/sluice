import { signal } from '@angular/core';
import { Observable } from 'rxjs';
import { MeResponse } from '../core/api/generated';
import { AuthStatus } from '../core/auth/auth.service';
import { BrowserLocation } from '../core/auth/browser-location';
import { Permission } from '../core/auth/permissions';

/** Records full-page navigations instead of leaving the test page. */
export class FakeLocation implements BrowserLocation {
  readonly assigned: string[] = [];
  pathname = '/';
  search = '';
  reloads = 0;

  assign(url: string | URL): void {
    this.assigned.push(String(url));
  }

  reload(): void {
    this.reloads++;
  }
}

/** An AuthService stand-in for components: a logged-in user with the given permissions. */
export function fakeAuth(permissions: Permission[], status: AuthStatus = 'authenticated') {
  const me: MeResponse = { name: 'Olivia Operator', permissions, language: null };
  return {
    status: signal(status).asReadonly(),
    me: signal(status === 'authenticated' ? me : null).asReadonly(),
    hasPermission: (permission: Permission) => permissions.includes(permission),
    saveLanguage: (): Observable<unknown> | null => null,
    logout: () => undefined,
  };
}
