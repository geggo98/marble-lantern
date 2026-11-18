// The child-process runner must report the same fingerprint as the in-process runner did. It uses the real engines, so it
// needs the built engine files and the table package (devenv tasks run web:engines web:tables); without them the test skips.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const webDir = fileURLToPath(new URL('..', import.meta.url));
const manifestPath = join(webDir, 'public', 'engines', 'engines.json');
const tablePath = join(webDir, 'public', 'tables', 'rows2.bin.gz');
const built = existsSync(manifestPath) && existsSync(tablePath);
const expected = JSON.parse(readFileSync(join(webDir, 'bench', 'expected.json'), 'utf8')) as { entries: Array<{ plies: number; nodes: number; checksum: string; totalNodes: number }> };
const smoke = expected.entries.find((e) => e.plies === 6 && e.nodes === 2000)!;

describe.skipIf(!built)('run-node.ts with one child process per engine (real engines)', () => {
  it('reports the fingerprint of expected.json for every engine, and real CPU and memory figures', () => {
    const dir = mkdtempSync(join(tmpdir(), 'bench-test-'));
    try {
      const json = join(dir, 'out.json');
      const run = spawnSync(
        process.execPath,
        ['--import', join(webDir, 'bench', 'register.mjs'), join(webDir, 'bench', 'run-node.ts'), '--plies', '6', '--nodes', '2000', '--runs', '1', '--json', json],
        { cwd: webDir, encoding: 'utf8', timeout: 110_000 },
      );
      expect(run.status, run.stderr).toBe(0);
      const result = JSON.parse(readFileSync(json, 'utf8')) as {
        v: number;
        environment: { processModel: string };
        engines: Array<{ id: string; status: string; games: Array<{ fingerprint: { checksum: string; totalNodes: number } }>; resources: Record<string, number | null> }>;
      };
      expect(result.v).toBe(1);
      expect(result.environment.processModel).toBe('one child process per engine');
      expect(result.engines.length).toBeGreaterThan(0);
      for (const e of result.engines) {
        expect(e.status, e.id).toBe('ok');
        expect(e.games[0]!.fingerprint).toEqual({ checksum: smoke.checksum, totalNodes: smoke.totalNodes });
        const r = e.resources;
        expect(r.cpuMs, e.id).toBeGreaterThan(0);
        expect(r.wallMs, e.id).toBeGreaterThan(0);
        expect(r.rssStartBytes!, e.id).toBeGreaterThan(10 * 1024 * 1024); // a Node.js process is never smaller
        expect(r.peakRssBytes!, e.id).toBeGreaterThanOrEqual(r.rssAfterLoadBytes!);
        expect(r.peakMemoryBytes).toBe(r.peakRssBytes);
      }
      expect(result.engines.find((e) => e.id === 'cpp-wasm')?.resources.wasmMemoryBytes).toBeGreaterThan(12 * 1024 * 1024); // holds the 12 MB table
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }, 120_000);
});

describe('process.resourceUsage().maxRSS', () => {
  it('is in KiB: times 1024 it is at least the current resident set size', () => {
    expect(process.resourceUsage().maxRSS * 1024).toBeGreaterThanOrEqual(process.memoryUsage.rss() * 0.95);
  });
});
