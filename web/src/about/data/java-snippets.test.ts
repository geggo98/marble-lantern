// The code snippets of the topic "java" are cut from the repository. This test reads every cited file and compares the cited lines with the
// text in data/java-snippets.json, so that a later edit of the Java code fails here instead of leaving a stale snippet on the page.
// Generated files (the Hilla client is git-ignored) and the illustration are not in the repository and are skipped, each by its flag.
// Seen red: change one line of a cited file, or one character of a snippet text, and the entry fails with its id and its path.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import data from './java-snippets.json';

interface Entry {
  id: string;
  lang: string;
  path?: string;
  startLine?: number;
  endLine?: number;
  text: string;
  generated?: boolean;
  illustration?: boolean;
}

const REPO = fileURLToPath(new URL('../../../../', import.meta.url));
const entries = data.snippets as Entry[];
const fromRepo = (e: Entry): string => readFileSync(`${REPO}${e.path}`, 'utf8').split('\n').slice(e.startLine! - 1, e.endLine).join('\n');

describe('java-snippets.json', () => {
  it('has the snippets the page asks for, with distinct ids', () => {
    const ids = entries.map((e) => e.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.sort()).toEqual(['a1', 'a2', 'a3', 'b1', 'b2', 'd1', 'd2', 'e1', 'e2', 'e3', 'h1', 'h3', 'hgen', 'illus'].sort());
  });

  it('marks exactly one generated entry (the Hilla client) and one illustration', () => {
    expect(entries.filter((e) => e.generated).map((e) => e.id)).toEqual(['hgen']);
    expect(entries.filter((e) => e.illustration).map((e) => e.id)).toEqual(['illus']);
    expect(entries.find((e) => e.id === 'illus')!.path).toBeUndefined();
  });

  const cut = entries.filter((e) => !e.generated && !e.illustration);

  it('counts the entries that are checked against the repository (12), so that a skip filter that matches nothing cannot pass', () => {
    expect(cut).toHaveLength(12);
  });

  describe.each(cut.map((e) => [`${e.id} ${e.path}:${e.startLine}-${e.endLine}`, e] as const))('%s', (_name, e) => {
    it('has a range inside the file and a text that equals those lines of the repository', () => {
      expect(e.startLine).toBeGreaterThan(0);
      expect(e.endLine).toBeGreaterThanOrEqual(e.startLine!);
      expect(e.text).toBe(fromRepo(e));
    });

    it('has as many lines as the range says', () => {
      expect(e.text.split('\n')).toHaveLength(e.endLine! - e.startLine! + 1);
    });
  });

  it('keeps the generated Hilla client as one line of a file under generated/', () => {
    const g = entries.find((e) => e.generated)!;
    expect(g.path).toMatch(/\/generated\/GameEndpoint\.ts$/);
    expect(g.text.split('\n')).toHaveLength(1);
    expect(g.text).toContain('client_1.call("GameEndpoint", "choose"');
  });

  it('says in the illustration that it is an illustration', () => {
    expect(entries.find((e) => e.illustration)!.text.split('\n')[0]).toMatch(/^\/\/ ILLUSTRATION/);
  });
});
