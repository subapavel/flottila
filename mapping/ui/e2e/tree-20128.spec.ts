import { writeFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { TREE_FIELDS, TREE_MAPPING } from '../src/api/fake/generated-tree';
import { collectProblems } from './problems';

// GATE check 7, tree target (ADR-0012, ADR-0013 "Performance"), on the Mapping studio (ADR-0028, #156, #161): the generated
// tree of 20,128 fields per side, both panes fully expanded as they open and every leaf linked, the Definition tab's source
// pane in Tree view scrolled top to bottom for 3 s, one scroll step per animation frame. The p95 of the frame intervals is
// the scenario's score in ms; headless Chromium caps frames at 60 Hz, so 16.7 ms is the floor. It keeps the old editor's
// scenario name and thresholds, and the virtualised pane keeps fewer than 50 rows in the DOM.
const SCROLL_MS = 3000;

test('ui/tree-20128/scroll', async ({ page }, testInfo) => {
  const problems = collectProblems(page);

  await page.goto(`/?api=fake&mapping=${encodeURIComponent(TREE_MAPPING)}#definition`);
  const pane = page.getByRole('region', { name: 'source message' });
  await pane.getByRole('group', { name: 'Source message view' }).getByRole('button', { name: 'Tree' }).click();
  const fields = pane.getByRole('table', { name: 'source fields' });
  await expect(fields.getByRole('button', { name: 'Tree', exact: true })).toBeVisible();
  // the source leaves are linked: the pane marks the fields the mapping reads; the target pane is open too
  await expect(fields.getByRole('img', { name: 'Connected' }).first()).toBeAttached();
  const targetFields = page.getByRole('region', { name: 'target message' }).getByRole('table', { name: 'target fields' });
  await expect(targetFields.getByRole('button', { name: /^Collapse / }).first()).toBeAttached();
  await expect(targetFields.getByRole('img', { name: 'Connected' }).first()).toBeAttached();
  const scroll = pane.locator('.fields-scroll');

  const run = await scroll.evaluate(
    (element, ms) =>
      new Promise<{ frames: number[]; rows: number; scrollHeight: number }>((resolve) => {
        const max = element.scrollHeight - element.clientHeight;
        const frames: number[] = [];
        let rows = 0;
        let start: number | undefined;
        let last = 0;
        const step = (now: number) => {
          if (start === undefined) start = now;
          else frames.push(now - last);
          last = now;
          const t = (now - start) / ms;
          if (t >= 1) {
            resolve({ frames, rows, scrollHeight: element.scrollHeight });
            return;
          }
          element.scrollTop = t * max;
          rows = Math.max(rows, element.querySelectorAll('tbody tr').length);
          requestAnimationFrame(step);
        };
        requestAnimationFrame(step);
      }),
    SCROLL_MS,
  );

  const sorted = [...run.frames].sort((a, b) => a - b);
  const p95 = sorted[Math.ceil(sorted.length * 0.95) - 1]!;
  const result = {
    scenario: 'ui/tree-20128/scroll',
    p95FrameMs: Number(p95.toFixed(1)),
    maxFrameMs: Number(sorted.at(-1)!.toFixed(1)),
    frames: run.frames.length,
    maxRowsInDom: run.rows,
  };
  console.log(`${result.scenario} p95 frame time ${result.p95FrameMs} ms (${JSON.stringify(result)})`);
  testInfo.annotations.push({ type: 'p95-frame-ms', description: String(result.p95FrameMs) });
  await testInfo.attach('tree-20128-scroll.json', { body: JSON.stringify(result, null, 2), contentType: 'application/json' });
  // the spec gate (scripts/gate-spec.sh step 5, #188) names a file here and takes the median of three runs; CI sets nothing
  const resultFile = process.env['TREE_P95_FILE'];
  if (resultFile) {
    writeFileSync(resultFile, JSON.stringify({ scenario: result.scenario, score: result.p95FrameMs, unit: 'ms' }));
  }

  // scrolled to the end, the last field's row is shown and the root's has left the DOM
  await scroll.evaluate((element) => (element.scrollTop = element.scrollHeight));
  await expect(fields.getByRole('button', { name: 'Tree', exact: true })).toHaveCount(0);
  await expect(fields.getByRole('row').last()).toBeVisible();
  expect(run.scrollHeight).toBeGreaterThanOrEqual(TREE_FIELDS * 20);
  expect(run.rows).toBeLessThan(50);
  expect(problems).toEqual([]);
});
