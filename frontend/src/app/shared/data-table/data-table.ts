import {
  AfterContentInit,
  ChangeDetectionStrategy,
  Component,
  ContentChildren,
  QueryList,
  ViewChild,
  input,
  output,
} from '@angular/core';
import { MatPaginatorIntl, MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatColumnDef, MatTable, MatTableModule } from '@angular/material/table';
import { TranslocoPipe } from '@jsverse/transloco';
import { EmptyState } from '../empty-state/empty-state';
import { ErrorState } from '../error-state/error-state';
import { Problem } from '../problem/problem';
import { TranslatedPaginatorIntl } from './translated-paginator-intl';

/**
 * The list layout every screen uses: a Material table with loading, empty and error states, and
 * server-side pagination (`Page` from the API: items, page, size, total).
 *
 * The caller declares its columns as ordinary `matColumnDef`s inside the tag and lists which to show:
 * ```html
 * <app-data-table [rows]="page.items" [columns]="['status', 'created']" [total]="page.total" ...>
 *   <ng-container matColumnDef="status">
 *     <th mat-header-cell *matHeaderCellDef>Status</th>
 *     <td mat-cell *matCellDef="let job">{{ job.status }}</td>
 *   </ng-container>
 * </app-data-table>
 * ```
 * Wide tables scroll sideways inside their own box, so the page itself never does (200 % zoom, phones).
 * The paginator's texts are translated here rather than app-wide, so its code loads with the first table,
 * not with the app shell.
 */
@Component({
  selector: 'app-data-table',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatTableModule,
    MatPaginatorModule,
    MatProgressBarModule,
    TranslocoPipe,
    EmptyState,
    ErrorState,
  ],
  providers: [{ provide: MatPaginatorIntl, useClass: TranslatedPaginatorIntl }],
  templateUrl: './data-table.html',
  styleUrl: './data-table.scss',
})
export class DataTable<T> implements AfterContentInit {
  readonly rows = input.required<readonly T[]>();
  readonly columns = input.required<readonly string[]>();
  /** Accessible name of the table, e.g. "Jobs". */
  readonly caption = input.required<string>();
  readonly total = input(0);
  readonly pageIndex = input(0);
  readonly pageSize = input(20);
  readonly pageSizeOptions = input<readonly number[]>([10, 20, 50, 100]);
  readonly loading = input(false);
  readonly error = input<Problem | null>(null);
  readonly emptyMessage = input.required<string>();

  readonly page = output<PageEvent>();
  readonly retry = output<void>();

  // Decorator queries: the projected column definitions must be registered before the table first renders.
  @ContentChildren(MatColumnDef) private readonly columnDefs!: QueryList<MatColumnDef>;
  @ViewChild(MatTable, { static: true }) private readonly table!: MatTable<T>;

  ngAfterContentInit(): void {
    this.columnDefs.forEach((def) => this.table.addColumnDef(def));
  }
}
