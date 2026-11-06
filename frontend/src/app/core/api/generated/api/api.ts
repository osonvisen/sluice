export * from './jobs.service';
import { JobsService } from './jobs.service';
export * from './system.service';
import { SystemService } from './system.service';
export const APIS = [JobsService, SystemService];
