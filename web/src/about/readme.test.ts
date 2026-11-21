// The README's "Source-code map" points new contributors at paths. A renamed or deleted file would leave the map
// pointing at nothing, and nobody reads the README after every refactoring. This test parses the table and checks
// every path in the "Path" and "Read this first" columns. Rows marked *git-ignored* name directories that only a
// build or a run creates, so for those it checks the `.gitignore` entry instead of the disk.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { ABOUT_TOPICS } from './routes';

const ROOT = fileURLToPath(new URL('../../../', import.meta.url));
const README = readFileSync(`${ROOT}README.md`, 'utf8');
const GITIGNORE = readFileSync(`${ROOT}.gitignore`, 'utf8').split('\n').map((l) => l.trim());

interface Row {
  line: string;
  paths: string[];
  readFirst: string[];
  ignored: boolean;
}

const codeSpans = (cell: string): string[] => [...cell.matchAll(/`([^`]+)`/g)].map((m) => m[1] ?? '');

function sourceMapRows(markdown: string): Row[] {
  const start = markdown.indexOf('\n## Source-code map\n');
  if (start < 0) return [];
  const next = markdown.indexOf('\n## ', start + 1);
  const section = markdown.slice(start, next < 0 ? undefined : next);
  return section
    .split('\n')
    .filter((l) => l.startsWith('|'))
    .slice(2) // header and separator
    .map((line) => {
      const cells = line.split('|').slice(1, -1);
      return {
        line,
        paths: codeSpans(cells[0] ?? ''),
        readFirst: codeSpans(cells[2] ?? ''),
        ignored: (cells[1] ?? '').includes('*git-ignored*'),
      };
    });
}

const rows = sourceMapRows(README);

describe('README source-code map', () => {
  it('has a table with a path and a first file in every row', () => {
    // Guards the parser: an empty table would make every check below pass.
    expect(rows.length).toBeGreaterThanOrEqual(15);
    for (const r of rows) {
      expect(r.paths, r.line).not.toHaveLength(0);
      expect(r.readFirst, r.line).not.toHaveLength(0);
    }
  });

  it('names only paths that exist in the repository', () => {
    const missing = rows
      .flatMap((r) => [...(r.ignored ? [] : r.paths), ...r.readFirst])
      .filter((p) => !existsSync(`${ROOT}${p}`));
    expect(missing).toEqual([]);
  });

  it('marks as git-ignored only paths that .gitignore lists', () => {
    const notIgnored = rows.filter((r) => r.ignored).flatMap((r) => r.paths).filter((p) => !GITIGNORE.includes(`/${p}`));
    expect(rows.some((r) => r.ignored)).toBe(true);
    expect(notIgnored).toEqual([]);
  });
});

describe('README links into the Info part', () => {
  it('links only to topics that the router knows', () => {
    const topics = [...README.matchAll(/#\/about\/([a-z0-9-]+)/g)].map((m) => m[1] ?? '');
    expect(topics.length).toBeGreaterThan(0);
    expect(topics.filter((t) => !(ABOUT_TOPICS as readonly string[]).includes(t))).toEqual([]);
  });
});
