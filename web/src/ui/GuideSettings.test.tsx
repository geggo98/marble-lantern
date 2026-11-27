// @vitest-environment jsdom
// The guide card with the level "net" (design: net hints, section 4): the 2 x 2 control, the disabled option and its reason, the legend, the net block.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import en from '../i18n/en.json';
import de from '../i18n/de.json';
import type { Divergence } from '../guides/divergence';
import type { NetAvailability } from '../guides/net-hints-types';
import type { GuideLevel } from '../guides/preference';
import { GuideSettings } from './GuideSettings';
import type { GuideSettingsNet } from './GuideSettings';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const ok: NetAvailability = { available: true, reason: null, lostInSession: false };
const trap: Divergence = { move: 1, netValue: -0.2, favouriteValue: 0.3, classicalBand: 'much-better' };
const fill = (text: string, values: Record<string, string | number>): string => text.replace(/\{(\w+)\}/g, (_w, k: string) => String(values[k]));

let host: HTMLElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

async function draw(level: GuideLevel, net: GuideSettingsNet | undefined, onChange: (l: GuideLevel) => void = () => undefined): Promise<void> {
  await act(async () => {
    root.render(<GuideSettings level={level} onChange={onChange} status="ready" {...(net ? { net } : {})} />);
  });
}
const radio = (level: string): HTMLInputElement => host.querySelector<HTMLInputElement>(`input[data-guides-level="${level}"]`)!;
const netOn = (over: Partial<GuideSettingsNet> = {}): GuideSettingsNet => ({ availability: ok, state: 'none', ...over });
const off = (reason: NetAvailability['reason'], lostInSession = false): GuideSettingsNet => ({ availability: { available: false, reason, lostInSession }, state: 'off' });

describe('the level control', () => {
  it('has four native radio buttons in the order none, targets, eval, net', async () => {
    await draw('targets', netOn());
    expect([...host.querySelectorAll('input[type="radio"]')].map((r) => r.getAttribute('data-guides-level'))).toEqual(['none', 'targets', 'eval', 'net']);
    expect(host.querySelector('.ml-guides-card__levels')!.getAttribute('role')).toBe('radiogroup');
  });

  it('checks the stored level and calls onChange for every available level, the net among them', async () => {
    const chosen: GuideLevel[] = [];
    await draw('eval', netOn(), (l) => chosen.push(l));
    expect(radio('eval').checked).toBe(true);
    for (const level of ['none', 'targets', 'net']) await act(async () => radio(level).click());
    expect(chosen).toEqual(['none', 'targets', 'net']);
  });

  it('has a label for the net level in both languages, at most 18 characters in German', () => {
    expect(en.guides_level_net.length).toBeGreaterThan(0);
    expect(de.guides_level_net.length).toBeLessThanOrEqual(18);
  });

  it('lays the options out as a 2 x 2 grid', async () => {
    await draw('targets', netOn());
    const css = readFileSync(resolve(process.cwd(), 'src/ui/guides.css'), 'utf8'); // vitest runs in web/
    expect(css).toMatch(/\.ml-guides-card__levels\s*\{[^}]*grid-template-columns:\s*repeat\(2, 1fr\)/);
  });
});

describe('the net option when it cannot be chosen', () => {
  const cases: [string, NetAvailability['reason'], string][] = [
    ['checking', { kind: 'checking' }, en.guides_net_reason_checking],
    ['three players', { kind: 'three-players' }, en.guides_net_reason_three],
    ['no WebGPU', { kind: 'reason', code: 'webgpu-no-api' }, en['net.reason.webgpu-no-api']],
    ['no model', { kind: 'reason', code: 'no-model-id' }, en['net.reason.no-model-id']],
  ];
  it.each(cases)('is aria-disabled but focusable, and names the reason (%s) through aria-describedby', async (_name, reason, text) => {
    await draw('targets', off(reason));
    const net = radio('net');
    expect(net.getAttribute('aria-disabled')).toBe('true');
    expect(net.disabled).toBe(false); // a native disabled radio would be skipped by the arrow keys
    const paragraph = host.querySelector(`[id="${net.getAttribute('aria-describedby')!}"]`)!;
    expect(paragraph.textContent).toBe(fill(en.guides_net_off, { reason: text }));
    expect(paragraph.querySelector('use')?.getAttribute('href')).toBe('#i-alert');
    expect(host.querySelector('.ml-guides-card__level:has(input[data-guides-level="net"])')!.getAttribute('aria-disabled')).toBe('true');
  });

  it('ignores a choice of the net: no onChange, and the radio stays unchecked', async () => {
    const chosen: GuideLevel[] = [];
    await draw('targets', off({ kind: 'three-players' }), (l) => chosen.push(l));
    await act(async () => radio('net').click());
    expect(chosen).toEqual([]);
    expect(radio('net').checked).toBe(false);
    expect(radio('targets').checked).toBe(true);
  });

  it('keeps the other levels working while the net is disabled', async () => {
    const chosen: GuideLevel[] = [];
    await draw('none', off({ kind: 'three-players' }), (l) => chosen.push(l));
    await act(async () => radio('eval').click());
    expect(chosen).toEqual(['eval']);
    expect(radio('eval').getAttribute('aria-disabled')).toBeNull();
  });

  it('shows the reason whichever level is chosen', async () => {
    for (const level of ['none', 'targets', 'eval'] as const) {
      await draw(level, off({ kind: 'reason', code: 'webgpu-no-adapter' }));
      expect(host.querySelector('[data-guides-net-off]'), level).not.toBeNull();
    }
  });

  it('shows no reason paragraph while the net is available', async () => {
    await draw('eval', netOn());
    expect(host.querySelector('[data-guides-net-off]')).toBeNull();
    expect(radio('net').getAttribute('aria-disabled')).toBeNull();
    expect(radio('net').hasAttribute('aria-describedby')).toBe(false);
  });

  it('shows eval as checked when the stored level is net but the GPU was lost, with the fallback note, and keeps the stored value out of it', async () => {
    await draw('net', off({ kind: 'reason', code: 'webgpu-lost' }, true));
    expect(radio('eval').checked).toBe(true);
    expect(radio('net').checked).toBe(false);
    expect(host.querySelector('[data-guides-level-now]')!.getAttribute('data-guides-level-now')).toBe('eval');
    const paragraph = host.querySelector('[data-guides-net-off]')!;
    expect(paragraph.textContent).toContain(en['net.reason.webgpu-lost']);
    expect(paragraph.textContent).toContain(en.guides_net_lost_fallback);
    expect(host.querySelector('.ml-guides-card__hint')!.textContent).toBe(en.guides_hint_eval);
  });

  it('without a net prop the net option is disabled and shows no reason', async () => {
    await draw('targets', undefined);
    expect(radio('net').getAttribute('aria-disabled')).toBe('true');
    expect(host.querySelector('[data-guides-net-off]')).toBeNull();
  });
});

describe('legend, hint and net block', () => {
  const legend = (): HTMLElement => host.querySelector<HTMLElement>('.ml-guides-card__legend')!;

  it('shows the five bands at eval and no net list', async () => {
    await draw('eval', netOn());
    expect(legend().querySelectorAll('[data-legend-band]')).toHaveLength(5);
    expect(legend().querySelector('[data-legend-net]')).toBeNull();
    expect(legend().querySelector('[data-legend-net-heading]')).toBeNull();
  });

  it('adds the second list at net: Falle first, then Spezialzug, with the heading and the note', async () => {
    await draw('net', netOn());
    expect(legend().querySelectorAll('[data-legend-band]')).toHaveLength(5);
    expect([...legend().querySelectorAll('[data-legend-net]')].map((li) => li.getAttribute('data-legend-net'))).toEqual(['trap', 'special']);
    expect(legend().querySelector('[data-legend-net-heading]')!.textContent).toBe(en.guides_legend_net_heading);
    expect(legend().textContent).toContain(en.guides_legend_trap);
    expect(legend().textContent).toContain(en.guides_legend_special);
    expect(legend().textContent).toContain(en.guides_legend_net_note);
    const glyphs = [...legend().querySelectorAll('[data-legend-net] [data-net-glyph]')].map((g) => g.getAttribute('data-net-glyph'));
    expect(glyphs).toEqual(['trap', 'special']);
  });

  it('shows no legend at the levels none and targets', async () => {
    for (const level of ['none', 'targets'] as const) {
      await draw(level, netOn());
      expect(host.querySelector('.ml-guides-card__legend'), level).toBeNull();
    }
  });

  it('shows the hint of the level net', async () => {
    await draw('net', netOn());
    expect(host.querySelector('.ml-guides-card__hint')!.textContent).toBe(en.guides_hint_net);
  });

  it('shows the net block under the status line at net on wide screens, and not on small ones or at other levels', async () => {
    await draw('net', netOn({ block: true, blockId: 'blk', state: 'marks', trap }));
    const block = host.querySelector('#blk')!;
    expect(block).not.toBeNull();
    expect(block.previousElementSibling?.getAttribute('role')).toBe('status');
    expect(block.querySelector('[data-nethint-row="trap"]')).not.toBeNull();
    await draw('net', netOn({ block: false, blockId: 'blk' }));
    expect(host.querySelector('#blk')).toBeNull();
    await draw('eval', netOn({ block: true, blockId: 'blk' }));
    expect(host.querySelector('#blk')).toBeNull();
  });

  it('treats the net like eval for the status line and the notes (evaluating is true)', async () => {
    await act(async () => {
      root.render(<GuideSettings level="net" onChange={() => undefined} status="analysing" three net={netOn()} />);
    });
    expect(host.querySelector('.ml-guides-card__status')!.textContent).toBe(en.guides_analysing);
    expect(host.querySelector('[data-guides-note-three]')).not.toBeNull();
  });
});

describe('German', () => {
  it('has the same keys as English for the net texts', () => {
    const keys = Object.keys(en).filter((k) => k.startsWith('nethint') || k.startsWith('guides_net') || k.startsWith('guides_legend_net') || k === 'guides_level_net' || k === 'guides_hint_net');
    expect(keys.length).toBeGreaterThan(20);
    for (const key of keys) expect(de, key).toHaveProperty(key);
  });
});
