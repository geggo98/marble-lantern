// @vitest-environment jsdom
// The model and simulation groups: strength statements come only from the manifest, with their variant label; presets are neutral
// (no Elo gain per preset); the typical time per move is shown for the CPU backend only.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';
import { listModels } from '../engines/net/model-manifest';
import type { ModelInfo } from '../engines/net/model-manifest';
import type { Sims } from '../engines/warnings';
import type { Variant } from '../game/variant';
import { ModelGroup, SimsGroup } from './NetGroups';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let root: Root | null = null;
afterEach(() => {
  act(() => root?.unmount());
  root = null;
  document.body.innerHTML = '';
});
async function render(node: React.JSX.Element): Promise<void> {
  const host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(node);
  });
}
const all = (sel: string): HTMLElement[] => Array.from(document.body.querySelectorAll<HTMLElement>(sel));
const q = (sel: string): HTMLElement | null => document.body.querySelector<HTMLElement>(sel);

const V2: Variant = { jump: 'adjacent', players: 2 };
const V3: Variant = { jump: 'adjacent', players: 3 };
const SHARED = listModels().find((m) => m.id === 'small-shared-all')!;
const EXPERT = listModels().find((m) => m.id === 'small-2p-adjacent-expert')!;
const model = (variant: Variant, models: readonly ModelInfo[], sims: Sims = 200): React.JSX.Element => (
  <ModelGroup variant={variant} all={models} models={models} modelId={null} sims={sims} onChange={() => undefined} />
);

describe('strength lines of a model', () => {
  it('2 players: every line says its player count and the simulations and opponent it was measured at', async () => {
    await render(model(V2, [SHARED]));
    const lines = all('[data-strength-fact]');
    expect(lines.map((l) => [l.dataset.strengthFact, l.dataset.players])).toEqual([['adjacent', '2'], ['long', '2']]);
    const adjacent = lines[0]!.textContent!;
    expect(adjacent).toContain('-123'); // Elo against 25,600 nodes
    expect(adjacent).toContain('25,600');
    expect(adjacent).toContain('200'); // simulations
  });

  it('3 players: the 3-player score of the manifest, no 2-player line', async () => {
    await render(model(V3, [SHARED]));
    const lines = all('[data-strength-fact]');
    expect(lines.map((l) => [l.dataset.strengthFact, l.dataset.players])).toEqual([['adjacent', '3'], ['long', '3']]);
    expect(lines[0]!.textContent).toContain('91'); // 0.913
    expect(lines[0]!.textContent).not.toContain('-123');
    expect(lines[0]!.textContent).not.toContain('25,600');
  });

  it('3 players and no 3-player measurement: says "not measured for this variant", never the 2-player figure', async () => {
    await render(model(V3, [EXPERT]));
    expect(all('[data-strength-fact]')).toHaveLength(0);
    const none = q('[data-strength-none]');
    expect(none).not.toBeNull();
    expect(none!.textContent).toContain('3');
    expect(document.body.textContent).not.toContain('490');
  });

  it('a line without an interval has no double space', async () => {
    await render(model(V2, [SHARED]));
    expect(all('[data-strength-fact]')[0]!.textContent).not.toMatch(/ {2}/);
  });

  it('the line of a tie follows the selected simulations', async () => {
    const info = { ...SHARED, strength: { mcts50_adjacent_elo: { vs_ab100: 1 }, mcts800_adjacent_elo: { vs_ab100: 3 } } } as ModelInfo;
    await render(model(V2, [info], 50));
    expect(q('[data-strength-fact]')!.textContent).toContain('50');
    expect(q('[data-strength-fact]')!.textContent).not.toContain('800');
  });
});

describe('simulation presets', () => {
  it('claim no Elo gain: only the simulations, in neutral labels', async () => {
    await render(<SimsGroup sims={200} backend="wasm" onChange={() => undefined} />);
    expect(all('[data-sims]').map((e) => e.dataset.sims)).toEqual(['50', '200', '800']);
    const text = q('[data-net-group="sims"]')!.textContent!;
    for (const claim of ['255', '390', 'Elo', 'net.sims.elo', 'net.sims.fast', 'net.sims.vstrong']) expect(text).not.toContain(claim);
  });

  it('the CPU backend shows the typical time per move, marked as typical', async () => {
    await render(<SimsGroup sims={200} backend="wasm" onChange={() => undefined} />);
    const times = all('[data-sims-time]');
    expect(times.map((e) => e.dataset.simsTime)).toEqual(['50', '200', '800']);
    expect(times.map((e) => /\b(15|58|241)\b/.exec(e.textContent!)?.[1])).toEqual(['15', '58', '241']);
    expect(times[0]!.textContent).toContain('ms per move on a fast desktop CPU');
  });

  it('WebGPU shows no time figure', async () => {
    await render(<SimsGroup sims={200} backend="webgpu" onChange={() => undefined} />);
    expect(all('[data-sims-time]')).toHaveLength(0);
    expect(q('[data-net-group="sims"]')!.textContent).not.toMatch(/\b(15|58|241)\b/);
  });
});
