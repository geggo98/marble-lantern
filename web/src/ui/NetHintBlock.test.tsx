// @vitest-environment jsdom
// The net block and the chip (design: net hints, sections 3 and 4.4).
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import en from '../i18n/en.json';
import type { Divergence } from '../guides/divergence';
import type { NetHintState } from '../guides/net-hints-types';
import { NetHintBlock, netPercent } from './NetHintBlock';
import { NetHintChip } from './NetHintChip';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const trap: Divergence = { move: 1, netValue: -0.2, favouriteValue: 0.3, classicalBand: 'much-better' }; // 40 % against 65 %
const special: Divergence = { move: 2, netValue: 0.1, favouriteValue: 0.1, classicalBand: 'worse' };
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
const render = async (node: React.JSX.Element): Promise<void> => {
  await act(async () => root.render(node));
};
const text = (selector: string): string => host.querySelector(selector)?.textContent ?? '';

describe('netPercent', () => {
  it.each([
    [-0.2, 40],
    [0.3, 65],
    [0, 50],
    [0.02, 50], // 51 % rounds to the nearest 5
    [0.06, 55], // 53 % -> 55
    [-1, 5], // 0 % clamps to 5
    [1, 95], // 100 % clamps to 95
    [-0.95, 5],
    [0.9, 95],
  ])('maps the value %s to %s %%', (v, expected) => {
    expect(netPercent(v)).toBe(expected);
  });
});

describe('NetHintBlock', () => {
  it('names a Falle with the band and two rounded win chances, never the favourite move', async () => {
    await render(<NetHintBlock state="marks" trap={trap} special={null} />);
    const row = host.querySelector('[data-nethint-row="trap"]')!;
    expect(row.textContent).toContain(en.nethint_trap_name);
    expect(row.textContent).toContain(fill(en.nethint_trap_reason, { band: en.guide_move_much_better, move: 40, favourite: 65 }));
    expect(row.textContent).toContain(en.nethint_trap_prompt);
    expect(host.querySelector('[data-nethint-row="special"]')).toBeNull();
    expect(host.querySelector('.ml-nethint-block__caveat')?.textContent).toBe(en.nethints_caveat);
  });

  it('names a Spezialzug with the band and no percentage', async () => {
    await render(<NetHintBlock state="marks" trap={null} special={special} />);
    const row = host.querySelector('[data-nethint-row="special"]')!;
    expect(row.textContent).toContain(fill(en.nethint_special_reason, { band: en.guide_move_worse }));
    expect(row.textContent).not.toMatch(/\d\s*%/);
    expect(row.textContent).toContain(en.nethint_special_prompt);
  });

  it('lists the Falle before the Spezialzug', async () => {
    await render(<NetHintBlock state="marks" trap={trap} special={special} />);
    expect([...host.querySelectorAll('[data-nethint-row]')].map((r) => r.getAttribute('data-nethint-row'))).toEqual(['trap', 'special']);
  });

  it.each(['idle', 'loading', 'thinking', 'none', 'skipped', 'failed'] as NetHintState[])('shows only the state text in the state %s, even when marks are passed', async (state) => {
    await render(<NetHintBlock state={state} trap={trap} special={special} />);
    expect(host.querySelectorAll('[data-nethint-row]')).toHaveLength(0);
    expect(text('[data-nethint-state-text]')).toBe(en[`nethints_state_${state}` as keyof typeof en]);
    expect(host.querySelector('.ml-nethint-block__caveat')).toBeNull();
  });

  it('shows the frame sentence with the reason and the fallback note when the GPU is lost', async () => {
    await render(<NetHintBlock state="unavailable" unavailableReason={en['net.reason.webgpu-lost']} />);
    const shown = text('[data-nethint-state-text]');
    expect(shown).toContain(fill(en.guides_net_off, { reason: en['net.reason.webgpu-lost'] }));
    expect(shown).toContain(en.guides_net_lost_fallback);
  });

  it('shows nothing at the state off', async () => {
    await render(<NetHintBlock state="off" />);
    expect(host.querySelector('[data-nethint-state-text]')).toBeNull();
  });

  it('can be focused by the chip: it has the given id and a tab index of -1', async () => {
    await render(<NetHintBlock state="none" id="blk" />);
    const block = host.querySelector('#blk')!;
    expect(block.getAttribute('tabindex')).toBe('-1');
  });
});

describe('NetHintChip', () => {
  const noop = (): void => undefined;

  it('is not rendered at the state off', async () => {
    await render(<NetHintChip state="off" marks={[]} onToggle={noop} />);
    expect(host.querySelector('button')).toBeNull();
    expect(host.querySelector('[data-nethint-live]')).toBeNull();
  });

  it.each(['loading', 'thinking'] as NetHintState[])('is busy with dots at the state %s', async (state) => {
    await render(<NetHintChip state={state} marks={[]} onToggle={noop} />);
    const chip = host.querySelector('button')!;
    expect(chip.getAttribute('aria-busy')).toBe('true');
    expect(chip.querySelectorAll('.ml-nethint-chip__dots i')).toHaveLength(3);
  });

  it.each(['idle', 'none', 'skipped'] as NetHintState[])('is a muted dash and not busy at the state %s', async (state) => {
    await render(<NetHintChip state={state} marks={[]} onToggle={noop} />);
    const chip = host.querySelector('button')!;
    expect(chip.getAttribute('aria-busy')).toBe('false');
    expect(chip.querySelector('.ml-nethint-chip__dash')).not.toBeNull();
  });

  it.each(['failed', 'unavailable'] as NetHintState[])('shows the alert icon at the state %s', async (state) => {
    await render(<NetHintChip state={state} marks={[]} onToggle={noop} />);
    expect(host.querySelector('button use')?.getAttribute('href')).toBe('#i-alert');
  });

  it('shows the glyphs of the marks present, the Falle first as given', async () => {
    await render(<NetHintChip state="marks" marks={['trap', 'special']} onToggle={noop} />);
    expect([...host.querySelectorAll('button [data-net-glyph]')].map((g) => g.getAttribute('data-net-glyph'))).toEqual(['trap', 'special']);
  });

  it('names itself by the label and the state text', async () => {
    await render(<NetHintChip state="none" marks={[]} onToggle={noop} />);
    expect(host.querySelector('button')!.getAttribute('aria-label')).toBe(`${en.nethints_chip_label}: ${en.nethints_state_none}`);
  });

  it('reports aria-expanded and aria-controls only when given, and calls onToggle on a click', async () => {
    let clicks = 0;
    await render(<NetHintChip state="none" marks={[]} onToggle={() => clicks++} />);
    const bare = host.querySelector('button')!;
    expect(bare.hasAttribute('aria-expanded')).toBe(false);
    expect(bare.hasAttribute('aria-controls')).toBe(false);
    await render(<NetHintChip state="none" marks={[]} expanded controls="panel" onToggle={() => clicks++} />);
    const chip = host.querySelector('button')!;
    expect(chip.getAttribute('aria-expanded')).toBe('true');
    expect(chip.getAttribute('aria-controls')).toBe('panel');
    await act(async () => chip.click());
    expect(clicks).toBe(1);
  });

  it('keeps the live region right after the button, and fills it only at the state marks', async () => {
    await render(<NetHintChip state="thinking" marks={[]} live="Net hint at A1" onToggle={noop} />);
    expect(text('[data-nethint-live]')).toBe('');
    await render(<NetHintChip state="marks" marks={['trap']} live="Net hint at A1" onToggle={noop} />);
    const live = host.querySelector('[data-nethint-live]')!;
    expect(live.textContent).toBe('Net hint at A1');
    expect(live.getAttribute('role')).toBe('status');
    expect(live.previousElementSibling?.tagName).toBe('BUTTON');
    await render(<NetHintChip state="none" marks={[]} live="Net hint at A1" onToggle={noop} />);
    expect(text('[data-nethint-live]')).toBe('');
  });
});
