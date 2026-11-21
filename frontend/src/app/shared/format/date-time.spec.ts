import { formatDateTime, formatRelativeTime } from './date-time';

describe('formatRelativeTime', () => {
  const now = new Date('2026-11-20T12:00:00Z');

  it('uses minutes for the near future, in the chosen language', () => {
    expect(formatRelativeTime('2026-11-20T12:03:00Z', now, 'en')).toBe('in 3 minutes');
    expect(formatRelativeTime('2026-11-20T12:03:00Z', now, 'nb')).toBe('om 3 minutter');
  });

  it('uses the largest unit that fits, also for the past', () => {
    expect(formatRelativeTime('2026-11-20T10:00:00Z', now, 'en')).toBe('2 hours ago');
    expect(formatRelativeTime('2026-11-23T12:00:00Z', now, 'en')).toBe('in 3 days');
    expect(formatRelativeTime('2026-11-20T12:00:20Z', now, 'en')).toBe('in 20 seconds');
  });
});

describe('formatDateTime', () => {
  it('formats in the chosen language', () => {
    const iso = '2026-11-20T12:00:00Z';
    expect(formatDateTime(iso, 'en')).not.toBe(formatDateTime(iso, 'nb'));
    expect(formatDateTime(iso, 'nb')).toContain('2026');
  });
});
