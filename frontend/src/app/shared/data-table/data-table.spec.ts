import { Component, signal } from '@angular/core';
import { MatTableModule } from '@angular/material/table';
import { PageEvent } from '@angular/material/paginator';
import { render, screen } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';
import { translocoTesting } from '../../testing/i18n';
import { Problem } from '../problem/problem';
import { DataTable } from './data-table';

interface Row {
  name: string;
}

@Component({
  imports: [DataTable, MatTableModule],
  template: `
    <app-data-table
      caption="Fruit"
      emptyMessage="No fruit"
      [rows]="rows()"
      [columns]="['name']"
      [total]="total()"
      [pageSize]="2"
      [error]="error()"
      (page)="pages.push($event)"
      (retry)="retries = retries + 1"
    >
      <ng-container matColumnDef="name">
        <th mat-header-cell *matHeaderCellDef>Name</th>
        <td mat-cell *matCellDef="let row">{{ row.name }}</td>
      </ng-container>
    </app-data-table>
  `,
})
class Host {
  readonly rows = signal<Row[]>([{ name: 'Apple' }, { name: 'Pear' }]);
  readonly total = signal(3);
  readonly error = signal<Problem | null>(null);
  readonly pages: PageEvent[] = [];
  retries = 0;
}

describe('DataTable', () => {
  it('renders the caller’s columns with an accessible name, and pages', async () => {
    const user = userEvent.setup();
    const { fixture } = await render(Host, { imports: [translocoTesting()] });

    expect(screen.getByRole('table', { name: 'Fruit' })).toBeTruthy();
    expect(screen.getByRole('cell', { name: 'Pear' })).toBeTruthy();
    expect(screen.getByText('1–2 of 3')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Next page' }));

    expect(fixture.componentInstance.pages[0].pageIndex).toBe(1);
  });

  it('says so when there are no rows', async () => {
    const { fixture } = await render(Host, { imports: [translocoTesting()] });
    fixture.componentInstance.rows.set([]);
    fixture.componentInstance.total.set(0);
    fixture.detectChanges();

    expect(screen.getByRole('status').textContent).toContain('No fruit');
  });

  it('shows the problem and lets the user try again', async () => {
    const user = userEvent.setup();
    const { fixture } = await render(Host, { imports: [translocoTesting()] });
    fixture.componentInstance.error.set({
      status: 500,
      title: 'Something broke',
      correlationId: 'c-1',
    });
    fixture.detectChanges();

    expect(screen.getByRole('alert').textContent).toContain('Something broke');
    expect(screen.getByRole('alert').textContent).toContain('c-1');
    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(fixture.componentInstance.retries).toBe(1);
  });
});
