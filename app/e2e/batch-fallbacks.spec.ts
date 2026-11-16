import type { Page } from '@playwright/test';
import { batchId, doneRow, makeBatch, pendingRow, serveBatch } from './batches';
import { jobId, serveMedia } from './generation';
import { expect, test } from './mock-api';

// Round 14 brief 04: a batch created with "fallbacks": true retries a row
// whose job failed on a provider error once, on another model. Its page says
// which model each row's job ran on, and links a retried row to the job that
// failed. A batch without fallbacks keeps its table as it was.

const rowsRegion = (page: Page) => page.getByRole('region', { name: 'Rows of the batch' });

async function open(page: Page, url: string) {
  await serveMedia(page);
  await page.goto(url);
  await expect(rowsRegion(page).locator('tbody tr').first()).toBeVisible();
}

test.describe('a batch with fallbacks', () => {
  test('says which model each row ran on, and links a retried row to the job that failed', async ({ page, api }) => {
    await api.signIn();
    serveBatch(api, {
      batch: makeBatch({
        model: 'gpt-image-2', status: 'RUNNING', rows_total: 3, rows_done: 2, rows_failed: 0, rows_running: 0,
        fallbacks: true,
      }),
      rows: [
        doneRow(0),
        doneRow(1, { model: 'gpt-image-1.5', retried_job_id: jobId(2001) }),
        pendingRow(2),
      ],
    });

    await open(page, `/batches/${batchId(1)}`);

    await expect(rowsRegion(page).getByRole('columnheader'))
      .toHaveText(['Row', 'Values', 'Status', 'Model', 'Output', 'Cost', 'Error']);
    const first = rowsRegion(page).locator('tr[data-row="1"]');
    await expect(first).toContainText('gpt-image-2');
    await expect(first).not.toContainText('failed');
    const retried = rowsRegion(page).locator('tr[data-row="2"]');
    await expect(retried).toContainText('gpt-image-1.5');
    await expect(retried.getByRole('link', { name: 'Row 2: open the generation that failed on gpt-image-2' }))
      .toHaveAttribute('href', `/history/${jobId(2001)}`);
    // A row not started yet has no model to show.
    await expect(rowsRegion(page).locator('tr[data-row="3"]').getByRole('cell').nth(2)).toHaveText('—');
  });

  test('a batch without fallbacks keeps its table as it was', async ({ page, api }) => {
    await api.signIn();
    serveBatch(api, {
      batch: makeBatch({ rows_total: 1, rows_done: 1, rows_failed: 0, rows_running: 0 }),
      rows: [doneRow(0)],
    });

    await open(page, `/batches/${batchId(1)}`);

    await expect(rowsRegion(page).getByRole('columnheader'))
      .toHaveText(['Row', 'Values', 'Status', 'Output', 'Cost', 'Error']);
  });
});
