import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { Permission } from './permissions';

/**
 * Keeps users out of screens they have no permission for, and sends them home, which says so.
 * Only a convenience: the API checks every request.
 */
export function requirePermission(permission: Permission): CanActivateFn {
  return () => inject(AuthService).hasPermission(permission) || inject(Router).createUrlTree(['/']);
}
