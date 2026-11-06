import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { acceptLanguageInterceptor } from './accept-language.interceptor';
import { I18N_CONFIG } from './i18n.providers';
import { LanguageService } from './language.service';

describe('acceptLanguageInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { en: {}, nb: {} },
          translocoConfig: I18N_CONFIG,
        }),
      ],
      providers: [
        provideHttpClient(withInterceptors([acceptLanguageInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('sends the active language to the api', () => {
    TestBed.inject(LanguageService).use('nb');

    http.get('/api/app-info').subscribe();

    expect(backend.expectOne('/api/app-info').request.headers.get('Accept-Language')).toBe('nb');
  });

  it('leaves non-api requests alone', () => {
    http.get('i18n/en.json').subscribe();

    expect(backend.expectOne('i18n/en.json').request.headers.has('Accept-Language')).toBe(false);
  });
});
