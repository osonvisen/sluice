import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApi } from '../api/generated';
import { LanguageService } from '../i18n/language.service';
import { FakeLocation } from '../../testing/fakes';
import { translocoTesting } from '../../testing/i18n';
import { AuthService } from './auth.service';
import { BROWSER_LOCATION } from './browser-location';
import { LOGIN_PATH } from './login-redirect';
import { Permission } from './permissions';
import { unauthorizedInterceptor } from './unauthorized.interceptor';

describe('login and session', () => {
  let backend: HttpTestingController;
  let location: FakeLocation;

  beforeEach(() => {
    sessionStorage.clear();
    location = new FakeLocation();
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [
        provideHttpClient(withInterceptors([unauthorizedInterceptor])),
        provideHttpClientTesting(),
        provideApi({ basePath: '' }),
        { provide: BROWSER_LOCATION, useValue: location },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('loads the user, their permissions and their stored language', async () => {
    const auth = TestBed.inject(AuthService);

    const loading = auth.load();
    backend
      .expectOne('/api/me')
      .flush({ name: 'Olivia Operator', permissions: ['jobs.read', 'jobs.retry'], language: 'nb' });
    await loading;

    expect(auth.status()).toBe('authenticated');
    expect(auth.hasPermission(Permission.JobsRetry)).toBe(true);
    expect(auth.hasPermission(Permission.ApprovalsDecide)).toBe(false);
    expect(TestBed.inject(LanguageService).current()).toBe('nb');
  });

  it('sends the whole page to the login when the api answers 401', () => {
    TestBed.inject(HttpClient)
      .get('/api/jobs')
      .subscribe({ error: () => undefined });

    backend.expectOne('/api/jobs').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(location.assigned).toEqual([LOGIN_PATH]);
  });

  it('stops instead of looping when the login sends us straight back without a session', async () => {
    const http = TestBed.inject(HttpClient);
    http.get('/api/me').subscribe({ error: () => undefined });
    backend.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    const auth = TestBed.inject(AuthService);
    const loading = auth.load();
    backend.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await loading;

    expect(location.assigned).toEqual([LOGIN_PATH]);
    expect(auth.status()).toBe('loginFailed');
  });

  it('ignores 401 from outside the api', () => {
    TestBed.inject(HttpClient)
      .get('/i18n/en.json')
      .subscribe({ error: () => undefined });

    backend.expectOne('/i18n/en.json').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(location.assigned).toEqual([]);
  });

  it('says the backend is unavailable when me cannot be loaded for another reason', async () => {
    const auth = TestBed.inject(AuthService);

    const loading = auth.load();
    backend.expectOne('/api/me').flush(null, { status: 503, statusText: 'Unavailable' });
    await loading;

    expect(auth.status()).toBe('unavailable');
  });

  it('logs out at the identity provider when the backend hands over its logout url', () => {
    TestBed.inject(AuthService).logout();

    backend.expectOne({ method: 'POST', url: '/logout' }).flush(null, {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: 'http://idp/logout?id_token_hint=x' },
    });

    expect(location.assigned).toEqual(['http://idp/logout?id_token_hint=x']);
  });

  it('just reloads after logout when there is no identity provider (local-noauth)', () => {
    TestBed.inject(AuthService).logout();

    backend.expectOne('/logout').flush(null, { status: 204, statusText: 'No Content' });

    expect(location.reloads).toBe(1);
  });

  it('saves the language only for a logged-in user', async () => {
    const auth = TestBed.inject(AuthService);
    expect(auth.saveLanguage('nb')).toBeNull();

    const loading = auth.load();
    backend.expectOne('/api/me').flush({ name: 'A', permissions: [], language: null });
    await loading;
    auth.saveLanguage('nb')?.subscribe();

    const request = backend.expectOne({ method: 'PUT', url: '/api/me/language' });
    expect(request.request.body).toEqual({ language: 'nb' });
    request.flush(null, { status: 204, statusText: 'No Content' });
  });
});
