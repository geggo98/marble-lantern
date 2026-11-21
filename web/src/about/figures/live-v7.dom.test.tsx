// @vitest-environment jsdom
// V7 (node budget) and the root-scores board (Fig. 2.1b) in jsdom, with the real catalogs, the live context and a scripted engine behind the
// real worker client: example mode, the disabled switch, nothing starts before the press, live data, the race case, Stop, the example board.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WorkerEngine } from '../../engines/client';
import type { ChooseResult } from '../../engines/engine';
import type { Request } from '../../engines/protocol';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from '../../engines/testing/fake-engine';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import { topic as classical } from '../topics/classical';
import type { AboutCatalog } from '../i18n/catalog';
import { LiveProvider } from '../live/context';
import type { LiveInput } from '../live/types';
import { BudgetFigure } from './BudgetFigure';
import { RootScoresFigure } from './RootScoresFigure';
import example from '../data/root-scores-mid.json';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const catalog = classical.catalog as AboutCatalog<string>;

/** The fake engine, with the depth of a choose() set by the test: depth = budget / 1000 unless `race` is set. */
class ScriptedCore extends FakeEngineCore {
  race = false;
  override handle(request: Request): unknown {
    const base = super.handle(request) as ChooseResult;
    if (request.type !== 'choose') return base;
    return { ...base, depth: Math.max(1, Math.round(Math.log10(base.nodes) * 2)), score: base.nodes, raceMove: this.race };
  }
}

let host: HTMLElement;
let root: Root;
let created = 0;
let core: ScriptedCore;
let delay = 0;

const sources = {
  classical: {
    id: 'cpp-wasm' as const,
    create: () => {
      created++;
      return new WorkerEngine({
        info: FAKE_INFO,
        assets: { engine: [], manifest: '', tableGz: '' },
        createWorker: () => new FakeWorker(core, { chooseDelayMs: delay }),
      });
    },
  },
  net: null,
};

const snapshot = (history: readonly number[], over = false, variant: Variant = DEFAULT_VARIANT): LiveInput => ({
  snapshot: { id: 1, history, variant, ply: history.length, over },
  sources: () => sources,
});

const render = async (node: React.JSX.Element, input: LiveInput | null): Promise<void> => {
  await act(async () => root.render(<LiveProvider input={input}>{node}</LiveProvider>));
};
const settle = async (ms = 0): Promise<void> => {
  await act(async () => {
    await new Promise((r) => setTimeout(r, ms));
  });
};
const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};
const all = (selector: string): Element[] => [...host.querySelectorAll(selector)];
const press = async (value: 'example' | 'mine'): Promise<void> => {
  await act(async () => q<HTMLInputElement>(`input[type=radio][value=${value}]`).click());
};
/** The data points of a series; the legend has a sample mark of the same class. */
const dots = (series: number): Element[] => all(`.ml-fig-plot__pt--${series}`).filter((e) => !e.closest('.ml-fig-legend'));
const status = (): string => q('[role=status]').textContent ?? '';

/** A few real opening moves, as the engine encodes them: from | to << 8 (taken from the 30 plies of eval-mid.json). */
const THREE: Variant = { jump: 'adjacent', players: 3 };
const TWO_MOVES = [3 | (16 << 8), 115 | (102 << 8)];

beforeEach(() => {
  created = 0;
  delay = 0;
  core = new ScriptedCore();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe('V7 live', () => {
  it('without a snapshot: Example is selected, My game is disabled with the reason, the plain series and the example chip show, no engine exists', async () => {
    await render(<BudgetFigure catalog={catalog} number={[2, 2]} />, null);
    expect(q<HTMLInputElement>('input[value=example]').checked).toBe(true);
    expect(q<HTMLInputElement>('input[value=mine]').disabled).toBe(true);
    expect(q('[data-live-reason]').textContent).toContain('Explain this position');
    expect(dots(2)).toHaveLength(5);
    expect(q('[data-origin]').textContent?.trim()).toBe('Example position, precomputed, ply 30');
    expect(created).toBe(0);
  });

  it('with a snapshot nothing starts until My game is pressed; then the five levels give five markers and the chip names the ply', async () => {
    await render(<BudgetFigure catalog={catalog} number={[2, 2]} />, snapshot(TWO_MOVES));
    await settle();
    expect(created, 'no engine before the press').toBe(0);
    expect(q<HTMLInputElement>('input[value=mine]').disabled).toBe(false);
    expect(q('input[value=mine]').closest('label')!.textContent).toBe('My game (ply 2)');

    await press('mine');
    await settle();
    await settle();
    expect(created).toBe(1);
    expect(q<HTMLInputElement>('input[value=mine]').checked).toBe(true);
    expect(dots(1)).toHaveLength(5);
    expect(dots(2), 'the series without enhancements has no live form').toHaveLength(0);
    expect(q('[data-live-note=plain]').textContent).toContain('exists for the example only');
    expect(q('[data-origin]').textContent?.trim()).toBe('Your game, ply 2, computed in your browser');
    expect(status()).toBe('Computed in your browser.');
    // the readout is the engine's, not the example's: the scripted score is the node budget of the level (level 3 = 20,000)
    expect(q('.ml-fig-budget__readout').textContent).toContain('20,000 nodes: depth');
    expect(all('.ml-fig-budget__readout span')).toHaveLength(1);
  });

  it('a race position shows the race note and no depth markers', async () => {
    core.race = true;
    await render(<BudgetFigure catalog={catalog} number={[2, 2]} />, snapshot(TWO_MOVES));
    await press('mine');
    await settle();
    await settle();
    expect(q('[data-live-note=race]').textContent).toContain('race table');
    expect(dots(1)).toHaveLength(0);
    expect(all('.ml-fig-budget__readout span')).toHaveLength(0);
  });

  it('Stop during a run returns to the example and says Stopped; a second press starts again', async () => {
    delay = 20;
    await render(<BudgetFigure catalog={catalog} number={[2, 2]} />, snapshot(TWO_MOVES));
    await press('mine');
    await settle(5);
    expect(q<HTMLInputElement>('input[value=mine]').checked).toBe(true);
    await act(async () => q<HTMLButtonElement>('[data-live-stop]').click());
    await settle(60);
    expect(q<HTMLInputElement>('input[value=example]').checked).toBe(true);
    expect(status()).toBe('Stopped. The figure shows the example again.');
    expect(dots(2)).toHaveLength(5); // the example is back
    await press('mine');
    await settle(200);
    expect(dots(1)).toHaveLength(5);
    expect(q<HTMLInputElement>('input[value=mine]').checked).toBe(true);
  });

  it('a finished game and a three-player snapshot leave My game disabled with the reason', async () => {
    await render(<BudgetFigure catalog={catalog} number={[2, 2]} />, snapshot(TWO_MOVES, true));
    expect(q<HTMLInputElement>('input[value=mine]').disabled).toBe(true);
    expect(q('[data-live-reason]').textContent).toBe('The game is over, so there is no move left to explain.');
    await render(<BudgetFigure catalog={catalog} number={[2, 2]} />, snapshot(TWO_MOVES, false, THREE));
    expect(q('[data-live-reason]').textContent).toContain('Only for the classic game');
  });
});

describe('root-scores board (Fig. 2.1b)', () => {
  it('shows the example: label 2.1b, one disc per movable piece, three arrows, one table row per legal move', async () => {
    await render(<RootScoresFigure catalog={catalog} number={[2, 1]} />, null);
    expect(q('.ml-fig__label').textContent).toBe('Fig. 2.1b');
    const moves = example.moves;
    expect(all('tbody tr')).toHaveLength(moves.length);
    expect(all('.ml-fig-board__arrow')).toHaveLength(3);
    const movers = new Set(moves.map((m) => m.from));
    expect(all('[data-level]')).toHaveLength(movers.size);
    // the table is sorted best first and its first three rows are the arrows
    expect(all('tbody tr[data-rank]')).toHaveLength(3);
    expect(all('tbody tr')[0]!.querySelector('th')!.textContent).toBe(moves[0]!.text);
    expect(q('[data-origin]').textContent?.trim()).toBe('Example position, precomputed, ply 30');
  });

  it('My game computes the scores of the snapshot with one analyse call; before that the board shows no pieces and no table', async () => {
    delay = 20;
    await render(<RootScoresFigure catalog={catalog} number={[2, 1]} />, snapshot([]));
    await press('mine');
    await settle(5);
    expect(q('[data-roots]').getAttribute('data-roots')).toBe('waiting');
    expect(all('tbody tr')).toHaveLength(0);
    expect(all('[data-level]')).toHaveLength(0);
    await settle(100);
    expect(q('[data-roots]').getAttribute('data-roots')).toBe('ready');
    expect(core.analyseCalls).toBe(1);
    // the start position has 14 legal moves
    expect(all('tbody tr')).toHaveLength(14);
    expect(q('[data-origin]').textContent?.trim()).toBe('Your game, ply 0, computed in your browser');
    expect(all('.ml-fig-board__arrow')).toHaveLength(3);
    // pieces that cannot move and the opponent's pieces are marbles: 20 pieces in all
    expect(all('[data-level]').length + all('use[data-seat]').length).toBe(20);
  });
});
