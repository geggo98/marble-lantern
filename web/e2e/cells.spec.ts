// The board geometry is committed as cells.generated.json. The C++ module is the second source of the same geometry
// (shw_cell_q/r); both must agree, in the browser as in node.
import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { openApp } from './helpers';

interface CellRow { id: number; q: number; r: number }

test('cells.generated.json equals shw_cell_q/r of the C++ module', async ({ page, baseURL }) => {
  const expected = JSON.parse(readFileSync(new URL('../src/board/cells.generated.json', import.meta.url), 'utf8')) as CellRow[];
  expect(expected).toHaveLength(121);
  await openApp(page, 'en');
  const moduleUrl = new URL('engines/cpp/engine-cpp.js', baseURL).href;
  const actual = await page.evaluate(async (url) => {
    const mod = (await import(/* @vite-ignore */ url)) as { default: () => Promise<Record<string, (...a: number[]) => number>> };
    const m = await mod.default();
    const n = m._shw_cell_count!();
    const rows: { id: number; q: number; r: number }[] = [];
    for (let id = 0; id < n; id++) rows.push({ id, q: m._shw_cell_q!(id), r: m._shw_cell_r!(id) });
    return rows;
  }, moduleUrl);
  expect(actual).toEqual(expected.map(({ id, q, r }) => ({ id, q, r })));
});
