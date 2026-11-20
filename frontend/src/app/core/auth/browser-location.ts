import { DOCUMENT, InjectionToken, inject } from '@angular/core';

/** The parts of `window.location` the app uses for full-page navigation. A token, so tests can replace it. */
export type BrowserLocation = Pick<Location, 'assign' | 'reload'>;

export const BROWSER_LOCATION = new InjectionToken<BrowserLocation>('BROWSER_LOCATION', {
  providedIn: 'root',
  factory: () => inject(DOCUMENT).location,
});
