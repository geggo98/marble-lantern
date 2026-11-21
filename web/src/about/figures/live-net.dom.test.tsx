// @vitest-environment jsdom
// V10 (MCTS) and V9b (forward pass) in jsdom, with the real catalogs, the live context and a scripted net engine behind the real worker
// client: nothing starts before the press, the root table fills per finished k, Stop returns to the example, a model failure shows the model
// error and no numbers, V9b gets its fourth position.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WorkerEngine } from '../../engines/client';
import type { ChooseResult, NetForwardReport } from '../../engines/engine';
import type { Request } from '../../engines/protocol';
import { FAKE_INFO, FakeEngineCore, FakeWorker } from '../../engines/testing/fake-engine';
import { DEFAULT_VARIANT } from '../../game/variant';
import type { Variant } from '../../game/variant';
import golden from '../data/forward-mid.json';
import mctsGolden from '../data/mcts-mid.json';
import { LiveProvider } from '../live/context';
import type { LiveInput } from '../live/types';
import type { ForwardData } from './forward';
import { ForwardFigure } from './ForwardFigure';
import { McstFigure } from './McstFigure';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const G = golden as unknown as ForwardData;

/** A net engine: newGame keeps sims, choose answers a root over the legal moves (k visits on the first moves), netForward a report of the size of the figure. */
class ScriptedNet extends FakeEngineCore {
  sims = 1;
  warnings: { code: string }[] = [];
  forwardCalls = 0;
  override handle(request: Request): unknown {
    switch (request.type) {
      case 'newGame':
        super.handle(request);
        this.sims = request.setup.netOptions?.sims ?? 1;
        return { warnings: this.warnings };
      case 'choose': {
        const base = super.handle(request) as ChooseResult;
        const moves = (super.handle({ ...request, type: 'legalMoves' } as Request) as number[]).slice(0, 4);
        const visits = moves.map((_, i) => Math.floor(this.sims / moves.length) + (i < this.sims % moves.length ? 1 : 0));
        return {
          ...base,
          move: moves[0],
          net: { backend: 'wasm', sims: this.sims, batches: this.sims, root: { moves, visits, values: moves.map((_, i) => 0.5 - 0.1 * i) } },
        };
      }
      case 'netForward': {
        this.forwardCalls++;
        const moves = (super.handle({ ...request, type: 'legalMoves' } as Request) as number[]).slice(0, 4);
        const report: NetForwardReport = {
          modelId: 'small-2p-adjacent-expert',
          planes: G.planes.map((p) => [...p]),
          layers: G.layers.map((l) => ({ id: l.id, norm: [...l.norm] })),
          logitsNetOnly: { from: [], to: [] },
          policy: moves.map((move) => ({ move, prior: 1 / moves.length, priorNetOnly: 1 / moves.length })),
          values: [0.25, -0.25],
          valuesNetOnly: [0, 0],
          canonicalRotated: false,
        };
        return report;
      }
      default:
        return super.handle(request);
    }
  }
}

let host: HTMLElement;
let root: Root;
let created = 0;
let core: ScriptedNet;
let delay = 0;

const sources = {
  classical: null,
  net: {
    id: 'net-wasm' as const,
    create: () => {
      created++;
      return new WorkerEngine({ info: FAKE_INFO, assets: { engine: [], manifest: '', tableGz: '' }, createWorker: () => new FakeWorker(core, { chooseDelayMs: delay }) });
    },
  },
};

/** A real opening exchange, as the engine encodes it: from | to << 8 (taken from the 30 plies of eval-mid.json). */
const TWO_MOVES = [3 | (16 << 8), 115 | (102 << 8)];
const THREE: Variant = { jump: 'adjacent', players: 3 };

const snapshot = (history: readonly number[], over = false, variant: Variant = DEFAULT_VARIANT, net = true): LiveInput => ({
  snapshot: { id: 1, history, variant, ply: history.length, over },
  sources: () => ({ ...sources, net: net ? sources.net : null }),
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
const status = (): string => (q('[role=status]').textContent ?? '').replace(/^Step \d+ of \d+:/, '');
const rowVisits = (): string[] => all('.ml-fig-mcts__table tbody tr').map((tr) => tr.querySelectorAll('td')[1]!.textContent ?? '');

beforeEach(() => {
  created = 0;
  delay = 0;
  core = new ScriptedNet();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe('V10 live', () => {
  it('without a snapshot the example shows and My game is disabled; with one, nothing starts until the press', async () => {
    await render(<McstFigure />, null);
    expect(q<HTMLInputElement>('input[value=mine]').disabled).toBe(true);
    expect(q('[data-origin]').textContent?.trim()).toBe('Example position, precomputed, ply 30');
    await render(<McstFigure />, snapshot(TWO_MOVES));
    await settle();
    expect(created).toBe(0);
    expect(q<HTMLInputElement>('input[value=mine]').disabled).toBe(false);
  });

  it('with no net engine in the browser, a finished game and a three-player snapshot the switch stays disabled with the reason', async () => {
    await render(<McstFigure />, snapshot(TWO_MOVES, false, DEFAULT_VARIANT, false));
    expect(q('[data-live-reason]').textContent).toBe('The neural-network engine does not run in this browser.');
    await render(<McstFigure />, snapshot(TWO_MOVES, true));
    expect(q('[data-live-reason]').textContent).toBe('The game is over; there is no move to explain.');
    await render(<McstFigure />, snapshot(TWO_MOVES, false, THREE));
    expect(q('[data-live-reason]').textContent).toContain('Only for two players');
  });

  it('My game runs the 50 steps; the stepper ends on 50 with the visits of the root moves adding up to 50, the chip says "Your game", the trace below is marked as the example', async () => {
    await render(<McstFigure />, snapshot(TWO_MOVES));
    await press('mine');
    await settle(20);
    await settle(20);
    expect(created).toBe(1);
    expect(core.forwardCalls).toBe(1);
    expect(q<HTMLInputElement>('input[value=mine]').checked).toBe(true);
    expect(status()).toBe('Computed in your browser.');
    expect(q('.ml-fig-stepper__pos').textContent).toContain('50 / 50');
    expect(rowVisits().map(Number).reduce((a, b) => a + b, 0)).toBe(50);
    expect(all('.ml-fig-mcts__table tbody tr')).toHaveLength(4); // the scripted root has four moves
    expect(q('[data-origin]').textContent?.trim()).toBe('Your game, ply 2, computed in your browser');
    expect(q('[data-origin-trace]').textContent?.trim()).toBe('Example position (precomputed)');
    expect(all('[data-substep], .ml-fig-seg').length, 'the sub-step switch does not apply to the root-only live table').toBe(1); // the live switch only
    expect(q('.ml-fig-final').textContent).toContain('50');
  });

  it('stepping back shows the table after fewer simulations: the visits at step 4 add up to 4', async () => {
    await render(<McstFigure />, snapshot(TWO_MOVES));
    await press('mine');
    await settle(20);
    await settle(20);
    await act(async () => q<HTMLButtonElement>('.ml-fig-stepper button').click()); // to step 0
    for (let i = 0; i < 4; i++) await act(async () => (all('.ml-fig-stepper button').at(-1) as HTMLButtonElement).click());
    expect(q('.ml-fig-stepper__pos').textContent).toContain('4 / 50');
    expect(rowVisits().map(Number).reduce((a, b) => a + b, 0)).toBe(4);
  });

  it('the table fills while the run goes on: after a few steps the stepper shows that many, not 50, and Stop returns to the example with "Stopped."', async () => {
    delay = 15;
    await render(<McstFigure />, snapshot(TWO_MOVES));
    await press('mine');
    await settle(80);
    const pos = q('.ml-fig-stepper__pos').textContent ?? '';
    const finished = Number(/(\d+) \/ (\d+)/.exec(pos)![2]);
    expect(finished, 'a partial run').toBeLessThan(50);
    expect(q('[data-live-stop]')).toBeTruthy();
    expect(status()).toMatch(/^Computing … \d+ of 50$/);
    await act(async () => q<HTMLButtonElement>('[data-live-stop]').click());
    await settle(40);
    expect(q<HTMLInputElement>('input[value=example]').checked).toBe(true);
    expect(status()).toBe('Stopped.');
    expect(q('.ml-fig-stepper__pos').textContent).toContain(`/ ${mctsGolden.sims.length}`);
    expect(q('[data-origin]').textContent?.trim()).toBe('Example position, precomputed, ply 30');
    await press('mine');
    await settle(15 * 60);
    expect(q<HTMLInputElement>('input[value=mine]').checked).toBe(true);
  });

  it('a net that cannot load its model shows the model error and no numbers of a random move', async () => {
    core.warnings = [{ code: 'model-load-failed' }, { code: 'fallback-random' }];
    await render(<McstFigure />, snapshot(TWO_MOVES));
    await press('mine');
    await settle(20);
    await settle(20);
    expect(status()).toBe('The model could not be loaded.');
    expect(q<HTMLInputElement>('input[value=example]').checked).toBe(true);
    expect(q('[data-live-retry]')).toBeTruthy();
    expect(q('[data-origin]').textContent?.trim()).toBe('Example position, precomputed, ply 30');
    expect(core.forwardCalls).toBe(0);
  });
});

describe('V9b live', () => {
  const position = (): HTMLSelectElement => q<HTMLSelectElement>('select.ml-fig-select');
  const choose = async (value: string): Promise<void> => {
    await act(async () => {
      const s = position();
      const set = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')!.set!;
      set.call(s, value);
      s.dispatchEvent(new Event('change', { bubbles: true }));
    });
  };

  it('without a snapshot the selector has the three examples; with one it has a fourth, "My game (ply N)", and nothing starts', async () => {
    await render(<ForwardFigure />, null);
    expect([...position().options].map((o) => o.value)).toEqual(['start', 'mid', 'race']);
    await render(<ForwardFigure />, snapshot(TWO_MOVES));
    await settle();
    expect([...position().options].map((o) => [o.value, o.textContent])).toEqual([
      ['start', 'Start position'],
      ['mid', 'Mid-game, after 30 plies'],
      ['race', 'Race, after 106 plies'],
      ['mine', 'My game (ply 2)'],
    ]);
    expect(created).toBe(0);
  });

  it('choosing My game runs one netForward and shows its numbers: the chip says "Your game, ply 2", the policy list has the scripted moves, and an example brings the committed data back', async () => {
    await render(<ForwardFigure />, snapshot(TWO_MOVES));
    await choose('mine');
    await settle(20);
    await settle(20);
    expect(core.forwardCalls).toBe(1);
    expect(position().value).toBe('mine');
    expect(status()).toBe('Computed in your browser.');
    expect(q('[data-origin]').textContent?.trim()).toBe('Your game, ply 2, computed in your browser');
    expect(all('.ml-fig-policy li')).toHaveLength(4);
    await choose('mid');
    expect(position().value).toBe('mid');
    expect(q('[data-origin]').textContent?.trim()).toBe('Example position, precomputed, ply 30');
    expect(all('.ml-fig-policy li')).toHaveLength(5);
  });

  it('a model failure shows the model error and goes back to the example', async () => {
    core.warnings = [{ code: 'fallback-random' }];
    await render(<ForwardFigure />, snapshot(TWO_MOVES));
    await choose('mine');
    await settle(20);
    await settle(20);
    expect(status()).toBe('The model could not be loaded.');
    expect(position().value).toBe('mid');
    expect(q('[data-live-retry]')).toBeTruthy();
  });

  it('a blocked snapshot (finished game) shows the reason and no fourth position', async () => {
    await render(<ForwardFigure />, snapshot(TWO_MOVES, true));
    expect([...position().options].map((o) => o.value)).toEqual(['start', 'mid', 'race']);
    expect(q('[data-live-reason]').textContent).toBe('The game is over; there is no move to explain.');
  });
});
