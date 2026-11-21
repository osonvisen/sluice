import { DestroyRef, Signal, inject, signal } from '@angular/core';

/**
 * The current time, refreshed every [periodMs], so texts like "next attempt in 3 minutes" stay true while
 * the page is open. Call it in an injection context; the timer stops with the component.
 */
export function injectNow(periodMs = 30_000): Signal<Date> {
  const now = signal(new Date());
  const timer = setInterval(() => now.set(new Date()), periodMs);
  inject(DestroyRef).onDestroy(() => clearInterval(timer));
  return now.asReadonly();
}
