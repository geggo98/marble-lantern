// @vitest-environment jsdom
// V6 with the live switch: the wiring between the switch, the run state and the figure. The adapter itself is tested in about/live/eval.test.ts;
// here `runEvalLive` is replaced by a run that answers with a changed score, so that the figure's data source is visible on the screen.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { topic as classical } from '../topics/classical';
import type { AboutCatalog } from '../i18n/catalog';
import { DEFAULT_VARIANT } from '../../game/variant';
import { LiveProvider } from '../live/context';
import type { LiveInput } from '../live/types';
import raw from '../data/eval-mid.json';
import type { EvalData } from './eval-model';
import { EvalFigure } from './EvalFigure';

const hold = vi.hoisted(() => ({ release: null as null | ((v: unknown) => void), calls: 0 }));
vi.mock('../live/eval', () => ({
  runEvalLive: (): Promise<unknown> => {
    hold.calls++;
    return new Promise((resolve) => {
      hold.release = resolve;
    });
  },
}));

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const classic = classical.catalog as AboutCatalog<string>;
const EXAMPLE = raw as unknown as EvalData;
// the distance sum of the example is -9; with 0 the score drops by 233.5 (178.645 - 233.525 = -54.88)
const MINE: EvalData = { ...EXAMPLE, meta: { ...EXAMPLE.meta, ply: 4 }, features: EXAMPLE.features.map((f) => (f.name === 'dsum' ? { ...f, value: 0 } : f)) };
const MINE_SCORE = '-55';

let host: HTMLElement;
let root: Root;
beforeEach(() => {
  hold.release = null;
  hold.calls = 0;
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

const input = (over = false): LiveInput => ({
  snapshot: { id: 1, history: [], variant: DEFAULT_VARIANT, ply: 4, over },
  sources: () => ({ classical: { id: 'cpp-wasm', create: () => ({}) as never }, net: null }),
});
const render = async (live: LiveInput | null): Promise<void> => {
  await act(async () => root.render(<LiveProvider input={live}><EvalFigure catalog={classic} number={[2, 3]} /></LiveProvider>));
};
const q = <T extends Element = HTMLElement>(s: string): T => host.querySelector<T>(s)!;
const radio = (value: string): HTMLInputElement => q<HTMLInputElement>(`[data-live-switch] input[value="${value}"]`);
const score = (): string => Array.from(host.querySelectorAll('.ml-fig-readout__cell')).find((c) => c.querySelector('dt')!.textContent === 'Score')!.querySelector('dd')!.textContent!;
const press = async (el: HTMLElement): Promise<void> => {
  await act(async () => el.click());
};

describe('the live switch of V6', () => {
  it('without a snapshot My game is disabled with its reason and the example shows +179 with the example chip', async () => {
    await render(null);
    expect(radio('mine').disabled).toBe(true);
    expect(q('[data-live-reason]').textContent).toContain('Explain this position');
    expect(score()).toBe('+179');
    expect(q('[data-origin]').getAttribute('data-origin')).toBe('example');
    expect(hold.calls).toBe(0);
  });

  it('pressing My game starts the run, shows Stop and the loading status; the live data replaces the example when the run is done', async () => {
    await render(input());
    expect(radio('mine').disabled).toBe(false);
    expect(hold.calls).toBe(0); // nothing starts before the press
    await press(radio('mine'));
    expect(hold.calls).toBe(1);
    expect(radio('mine').checked).toBe(true);
    expect(q('[data-live-stop]')).not.toBeNull();
    expect(q('.ml-fig__status').textContent).toBe('Loading the engine; the first time, the browser may download it …');
    expect(score()).toBe('+179'); // still the example while the engine works
    await act(async () => hold.release!(MINE));
    expect(score()).toBe(MINE_SCORE);
    expect(q('[data-origin]').getAttribute('data-origin')).toBe('mine');
    expect(q('[data-origin]').textContent).toContain('Your game, ply 4');
    expect(host.querySelector('[data-live-stop]')).toBeNull();
    expect(host.querySelector('.ml-fig__caption')!.textContent).toContain('the score with linear-r3');
  });

  it('Example brings the example data back; My game again shows the finished result without a second run', async () => {
    await render(input());
    await press(radio('mine'));
    await act(async () => hold.release!(MINE));
    await press(radio('example'));
    expect(score()).toBe('+179');
    await press(radio('mine'));
    expect(hold.calls).toBe(1);
    expect(score()).toBe(MINE_SCORE);
  });

  it('Stop returns to the example with the status "Stopped."', async () => {
    await render(input());
    await press(radio('mine'));
    await press(q('[data-live-stop]'));
    expect(radio('example').checked).toBe(true);
    expect(q('.ml-fig__status').textContent).toBe('Stopped. The figure shows the example again.');
    expect(score()).toBe('+179');
  });

  it('a finished game disables My game with the reason', async () => {
    await render(input(true));
    expect(radio('mine').disabled).toBe(true);
    expect(q('[data-live-reason]').textContent).toContain('The game is over');
  });
});
