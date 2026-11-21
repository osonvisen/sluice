/**
 * Permissions from `GET /api/me`, the same strings as the backend's `Permissions` (ADR 0004).
 * The API enforces them; the UI only uses them to hide what the user cannot do.
 */
export const Permission = {
  JobsRead: 'jobs.read',
  JobsRetry: 'jobs.retry',
  ApprovalsDecide: 'approvals.decide',
} as const;

export type Permission = (typeof Permission)[keyof typeof Permission];
