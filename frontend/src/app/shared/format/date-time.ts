import { Pipe, PipeTransform } from '@angular/core';

/**
 * Dates and times in the reader's language and the browser's time zone (api-and-data standard: stored
 * in UTC, shown in the user's zone). Plain `Intl`, so Norwegian needs no extra Angular locale data.
 */
export function formatDateTime(iso: string, language: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  );
}

const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * "in 3 minutes", "2 hours ago", "om 3 minutter". Rounded to the largest whole unit that fits, which is
 * what a person reading a status wants; the exact time is shown next to it.
 */
export function formatRelativeTime(target: string, now: Date, language: string): string {
  const seconds = Math.round((new Date(target).getTime() - now.getTime()) / 1000);
  const format = new Intl.RelativeTimeFormat(language, { numeric: 'auto' });
  const size = Math.abs(seconds);
  if (size < MINUTE) {
    return format.format(seconds, 'second');
  }
  if (size < HOUR) {
    return format.format(Math.round(seconds / MINUTE), 'minute');
  }
  if (size < DAY) {
    return format.format(Math.round(seconds / HOUR), 'hour');
  }
  return format.format(Math.round(seconds / DAY), 'day');
}

/** `{{ job.createdAt | dateTime: language() }}`. Pure: the language is an argument, so a switch re-renders it. */
@Pipe({ name: 'dateTime' })
export class DateTimePipe implements PipeTransform {
  transform(iso: string | null | undefined, language: string): string {
    return iso ? formatDateTime(iso, language) : '';
  }
}
