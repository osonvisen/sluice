import { Injectable, OnDestroy, inject } from '@angular/core';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { TranslocoService } from '@jsverse/transloco';
import { Subscription } from 'rxjs';

/** The paginator's own texts ("Items per page", "1 – 20 of 135") through Transloco, updated on language change. */
@Injectable()
export class TranslatedPaginatorIntl extends MatPaginatorIntl implements OnDestroy {
  private readonly transloco = inject(TranslocoService);
  private readonly subscription: Subscription;

  constructor() {
    super();
    this.subscription = this.transloco.selectTranslation().subscribe(() => {
      this.itemsPerPageLabel = this.transloco.translate('table.itemsPerPage');
      this.nextPageLabel = this.transloco.translate('table.nextPage');
      this.previousPageLabel = this.transloco.translate('table.previousPage');
      this.firstPageLabel = this.transloco.translate('table.firstPage');
      this.lastPageLabel = this.transloco.translate('table.lastPage');
      this.changes.next();
    });
  }

  override getRangeLabel = (page: number, pageSize: number, length: number): string => {
    if (length === 0) {
      return this.transloco.translate('table.rangeEmpty');
    }
    const from = page * pageSize + 1;
    const to = Math.min(length, (page + 1) * pageSize);
    return this.transloco.translate('table.range', { from, to, total: length });
  };

  ngOnDestroy(): void {
    this.subscription.unsubscribe();
  }
}
