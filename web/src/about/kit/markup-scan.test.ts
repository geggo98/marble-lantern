// Leftover markup scan: every catalog value that goes through the markup renderer must leave no raw token ({sec:, {term:, {src:, {n:)
// in its text nodes. Regression test of B1: a section link whose text held `code` ("{sec:inference/mcts|how MCTS uses `c`}") was shown raw.
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { parseMarkup } from './markup';

const dir = (rel: string): string => fileURLToPath(new URL(rel, import.meta.url));
const LEFTOVER = /\{(sec|term|src|n):/;

/** The raw leftover tokens of one string, as the renderer would show them. */
function leftovers(value: string): string[] {
  const problems: string[] = [];
  for (const node of parseMarkup(value, () => undefined)) {
    if (node.type === 'text' && LEFTOVER.test(node.text)) problems.push(node.text);
    if ((node.type === 'sec' || node.type === 'term') && node.text !== undefined && LEFTOVER.test(node.text)) problems.push(node.text);
  }
  return problems;
}

describe('parseMarkup leaves no raw token', () => {
  it('resolves a section link whose text holds inline code', () => {
    const nodes = parseMarkup('x ({sec:inference/mcts|how MCTS uses `c`}) y', () => undefined);
    expect(nodes.find((n) => n.type === 'sec')).toEqual({ type: 'sec', topic: 'inference', section: 'mcts', text: 'how MCTS uses `c`' });
    expect(nodes.filter((n) => n.type === 'text').map((n) => (n as { text: string }).text).join('')).toBe('x () y');
  });

  it('resolves a section link inside parentheses', () => {
    expect(leftovers('see ({sec:inference/mcts|how MCTS works}).')).toEqual([]);
  });

  const files: string[] = [];
  for (const lang of ['en', 'de']) for (const f of readdirSync(dir(`../i18n/${lang}/`))) if (f.endsWith('.json')) files.push(`${lang}/${f}`);

  it.each(files)('catalog %s has no leftover token in any value', (file) => {
    const catalog = JSON.parse(readFileSync(dir(`../i18n/${file}`), 'utf8')) as Record<string, string>;
    const bad = Object.entries(catalog).flatMap(([key, value]) => (typeof value === 'string' ? leftovers(value).map((t) => `${key}: ${t}`) : []));
    expect(bad).toEqual([]);
  });
});
