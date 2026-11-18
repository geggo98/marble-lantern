// Runs loadTable over the real table package that `devenv tasks run web:tables` writes into web/public/tables/.
// Skipped (and shown as skipped) when the package is not there.
import { existsSync, readFileSync } from 'node:fs';
import { webcrypto } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import { loadTable } from './tables';

const dir = new URL('../../public/tables/', import.meta.url);
const present = existsSync(new URL('rows2.manifest.json', dir)) && existsSync(new URL('rows2.bin.gz', dir));

describe.skipIf(!present)('real table package', () => {
  const serve = (async (url: string) =>
    new Response(readFileSync(new URL(url.split('/').pop()!, dir)))) as unknown as typeof fetch;

  it('inflates and verifies rows2.bin (13,123,286 bytes, SHA-256 from docs/offline-mode.md)', async () => {
    const t = await loadTable({
      manifestUrl: 'tables/rows2.manifest.json', tableUrl: 'tables/rows2.bin.gz',
      engineChecksum: true, fetch: serve, subtle: webcrypto.subtle as SubtleCrypto,
    });
    expect(t.bytes.byteLength).toBe(13123286);
    expect(t.manifest.sha256).toBe('d62edf38bbccb229ca055da3054a11e6daffae817d2293dbfa523ad8bb3678e3');
    expect(t.verifiedBy).toBe('subtle-sha256');
  });
});
