// @vitest-environment jsdom
// The foot of a figure: the data chip names where the numbers come from (example, the reader's game, an example-only part), in both languages.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it } from 'vitest';
import { kitCatalog } from '../i18n/kit';
import type { Lookup } from './common';
import { FigFoot, originText } from './common';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const kit = (language: 'en' | 'de'): Lookup => (key, values) => (kitCatalog as unknown as { lookup: (l: string, k: string, v?: Record<string, string | number>) => string }).lookup(language, key, values);

async function foot(origin: Parameters<typeof FigFoot>[0]['origin']): Promise<HTMLElement> {
  const host = document.createElement('div');
  document.body.appendChild(host);
  await act(async () => createRoot(host).render(<FigFoot kit={kit('en')} paths={['a/B.java']} data="eval-mid.json" {...(origin ? { origin } : {})} />));
  return host;
}

describe('FigFoot data chip', () => {
  it('without an origin it is the data file chip, as before', async () => {
    const host = await foot(undefined);
    expect(host.querySelector('[data-origin]')).toBeNull();
    expect(host.textContent).toContain('eval-mid.json');
  });

  it('example: "Example position, precomputed, ply 30" and the file chip', async () => {
    const host = await foot({ kind: 'example', ply: 30 });
    expect(host.querySelector('[data-origin="example"]')?.textContent).toContain('Example position, precomputed, ply 30');
    expect(host.textContent).toContain('eval-mid.json');
  });

  it('mine: "Your game, ply N, computed in your browser", and no file chip (the file is not where these numbers come from)', async () => {
    const host = await foot({ kind: 'mine', ply: 4 });
    expect(host.querySelector('[data-origin="mine"]')?.textContent).toContain('Your game, ply 4, computed in your browser');
    expect(host.textContent).not.toContain('eval-mid.json');
    expect(host.textContent).toContain('a/B.java'); // the source chips stay
  });

  it('exampleOnly names the part that has no live form', async () => {
    const host = await foot({ kind: 'exampleOnly' });
    expect(host.querySelector('[data-origin="exampleOnly"]')?.textContent).toContain('Example position (precomputed)');
  });

  it('has the same placeholders in German', () => {
    expect(originText(kit('de'), { kind: 'example', ply: 30 })).toContain('30');
    expect(originText(kit('de'), { kind: 'mine', ply: 4 })).toContain('4');
    expect(originText(kit('de'), { kind: 'example', ply: 30 })).not.toContain('{ply}');
  });
});
