// @vitest-environment jsdom
// The figures of topics 1 and 2 in jsdom with the real catalogs: state machine of the steppers, switches and sliders, keyboard of the
// tree, and the numbers on the screen against the data files (V5 counter 55 of 210, V6 score +179). Layout needs a browser; the
// screenshot script web/e2e/about-shots.spec.ts covers it.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { topic as architecture } from '../topics/architecture';
import { topic as classical } from '../topics/classical';
import type { AboutCatalog } from '../i18n/catalog';
import { ArchFigure } from './ArchFigure';
import { TreeFigure } from './TreeFigure';
import { AlphaBetaFigure } from './AlphaBetaFigure';
import { EvalFigure } from './EvalFigure';
import { BudgetFigure } from './BudgetFigure';
import { edgeKey } from './arch-model';
import offline from '../data/arch-offline.json';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const arch = architecture.catalog as AboutCatalog<string>;
const classic = classical.catalog as AboutCatalog<string>;

let host: HTMLElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

const render = async (node: React.JSX.Element): Promise<void> => {
  await act(async () => root.render(node));
};
const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};
const byLabel = (label: string): HTMLElement => q(`[aria-label="${label}"]`);
const click = (el: Element): void => act(() => (el as HTMLElement).click());
const key = (el: Element, k: string): void => {
  act(() => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  });
};
const setRange = (el: HTMLInputElement, value: number): void => {
  act(() => {
    const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    set.call(el, String(value));
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  });
};
const states = (attr: string): Record<string, string | null> =>
  Object.fromEntries(Array.from(host.querySelectorAll<HTMLElement>(`[${attr}]`)).map((el) => [el.getAttribute(attr)!, el.getAttribute('data-state')]));
const status = (): string => q('.ml-fig__status').textContent ?? '';
const forward = (): void => click(byLabel('One step forward'));

describe('V1, V2, V3 (box diagrams)', () => {
  it('V1: idle shows no state; step 2 lights the request path, dims the rest and narrates it', async () => {
    await render(<ArchFigure catalog={arch} kind="offline" number={[1, 2]} />);
    expect(Object.values(states('data-box')).every((s) => s === null)).toBe(true);
    forward();
    forward();
    const s = states('data-box');
    expect(Object.keys(s)).toHaveLength(offline.boxes.length);
    expect(Object.entries(s).filter(([, v]) => v === 'active').map(([k]) => k).sort()).toEqual(['controller', 'session', 'workerClient']);
    expect(Object.entries(s).filter(([, v]) => v === 'dim')).toHaveLength(offline.boxes.length - 3);
    expect(status()).toContain('Step 2 of 6');
    expect(q('.ml-fig-stepper__pos').textContent).toContain('2 / 6');
    // the edges of step 2 are the 4th and 5th edge of the file
    const active = Array.from(host.querySelectorAll('g[data-edge] path[data-state="active"]')).map((p) => p.closest('g')!.getAttribute('data-edge'));
    expect([...new Set(active)]).toEqual([String(offline.edges.findIndex((e) => edgeKey(e) === 'controller-session')), String(offline.edges.findIndex((e) => edgeKey(e) === 'session-workerClient'))]);
  });

  it('V1: Home and End of the stepper go to step 1 and 6; reset returns to idle', async () => {
    await render(<ArchFigure catalog={arch} kind="offline" number={[1, 2]} />);
    const group = q('.ml-fig-stepper');
    key(group, 'End');
    expect(q('.ml-fig-stepper__pos').textContent).toContain('6 / 6');
    key(group, 'Home');
    expect(q('.ml-fig-stepper__pos').textContent).toContain('1 / 6');
    click(byLabel('Back to the start'));
    expect(q('.ml-fig-stepper__pos').textContent).toContain('0 / 6');
  });

  it('V1: the undo variant has 3 steps with its own texts and resets the stepper', async () => {
    await render(<ArchFigure catalog={arch} kind="offline" number={[1, 2]} />);
    key(q('.ml-fig-stepper'), 'End');
    click(q('.ml-fig-check input'));
    expect(q('.ml-fig-stepper__pos').textContent).toContain('0 / 3');
    forward();
    expect(status()).toContain('Step 1 of 3');
    expect(status()).toContain('You undo a move while the worker still searches');
    expect(Object.entries(states('data-box')).filter(([, v]) => v === 'active').map(([k]) => k).sort()).toEqual(['controller', 'page', 'workerClient']);
  });

  it('V1: the flow switch dims the edges of the other kind and a box opens its detail with source chips; Escape closes it', async () => {
    await render(<ArchFigure catalog={arch} kind="offline" number={[1, 2]} />);
    const radio = (label: string): HTMLInputElement => Array.from(host.querySelectorAll<HTMLInputElement>('.ml-fig-seg input')).find((i) => i.parentElement?.textContent === label)!;
    click(radio('Control flow'));
    const edges = Array.from(host.querySelectorAll<SVGGElement>('g[data-edge]'));
    expect(edges.filter((g) => g.getAttribute('data-kind') === 'data').every((g) => g.querySelector('path')!.getAttribute('data-state') === 'dim')).toBe(true);
    expect(edges.filter((g) => g.getAttribute('data-kind') === 'control').every((g) => g.querySelector('path')!.getAttribute('data-state') === null)).toBe(true);
    const box = q('[data-box="workerClient"]');
    click(box);
    expect(q('.ml-fig__detail-title').textContent).toBe('Worker client');
    expect(Array.from(host.querySelectorAll('.ml-fig__detail-chips a')).map((a) => a.lastElementChild!.lastElementChild!.textContent)).toContain('web/src/engines/client.ts');
    key(q('.ml-fig__detail'), 'Escape');
    expect(host.querySelector('.ml-fig__detail')).toBeNull();
  });

  it('V1 to V3 use the wide layout from the figure breakpoint (640 px) and the compact one below it, with the short box labels only in the compact one', async () => {
    let width = 700;
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width, height: 100, x: 0, y: 0, top: 0, left: 0, right: width, bottom: 100, toJSON: () => ({}) }));
    try {
      for (const kind of ['offline', 'online', 'pipeline'] as const) {
        width = 700;
        await act(async () => root.unmount());
        root = createRoot(host);
        await render(<ArchFigure catalog={arch} kind={kind} number={[1, 2]} />);
        expect(q('.ml-fig-boxwrap').getAttribute('data-layout'), kind).toBe('wide');
        if (kind === 'offline') expect(q('[data-box="page"]').textContent).toBe('Browser page (React)');
        width = 600;
        await act(async () => root.unmount());
        root = createRoot(host);
        await render(<ArchFigure catalog={arch} kind={kind} number={[1, 2]} />);
        expect(q('.ml-fig-boxwrap').getAttribute('data-layout'), kind).toBe('compact');
        if (kind === 'offline') expect(q('[data-box="page"]').textContent).toBe('Browser page');
      }
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('V1: the detail panel of a box opens directly under the diagram, before the legend and the list of edges', async () => {
    await render(<ArchFigure catalog={arch} kind="offline" number={[1, 2]} />);
    click(q('[data-box="workerClient"]'));
    const wrap = q('.ml-fig-boxwrap');
    const detail = q('.ml-fig__detail');
    expect(wrap.contains(detail)).toBe(true);
    expect(wrap.firstElementChild?.nextElementSibling?.contains(detail)).toBe(true);
    // the legend follows the diagram wrapper
    expect(wrap.parentElement?.nextElementSibling?.classList.contains('ml-fig-legend')).toBe(true);
  });

  it('V1: shows no source chip for a generated file and names the data file in a data chip', async () => {
    await render(<ArchFigure catalog={arch} kind="online" number={[1, 3]} />);
    click(q('[data-box="hillaClient"]'));
    expect(host.querySelectorAll('.ml-fig__detail-chips a')).toHaveLength(0);
    expect(q('.ml-fig-chip--data').textContent).toContain('arch-online.json');
  });

  it('V2: the slider changes the permits: 8 requests at 4 permits run 4 and make 4 wait', async () => {
    await render(<ArchFigure catalog={arch} kind="online" number={[1, 3]} />);
    const slider = q<HTMLInputElement>('input[type="range"]');
    expect(slider.getAttribute('aria-valuetext')).toBe('1 requests, 4 permits');
    expect(host.querySelectorAll('.ml-fig-permits__slot')).toHaveLength(4);
    expect(host.querySelector('.ml-fig-permits__busy')).toBeNull();
    setRange(slider, 8);
    expect(slider.getAttribute('aria-valuetext')).toBe('8 requests, 4 permits');
    expect(host.querySelectorAll('.ml-fig-permits__slot[data-state="held"]')).toHaveLength(4);
    expect(host.querySelectorAll('.ml-fig-permits__token')).toHaveLength(4);
    expect(q('.ml-fig-permits__counts').textContent).toBe('Running: 4Waiting: 4');
    expect(q('.ml-fig-permits__busy').textContent).toContain('30 s');
    setRange(slider, 5);
    expect(host.querySelectorAll('.ml-fig-permits__token')).toHaveLength(1);
  });

  it('V3: the CPU or GPU switch swaps the trainer box and the measured time of the loop run', async () => {
    await render(<ArchFigure catalog={arch} kind="pipeline" number={[1, 4]} />);
    expect(host.querySelector('[data-box="trainerCpu"]')).not.toBeNull();
    expect(host.querySelector('[data-box="trainerGpu"]')).toBeNull();
    expect(host.querySelector('[data-box="cloud"]')).toBeNull();
    expect(q('.ml-fig-timeline__value').textContent).toBe('Training per generation: 22.9 min');
    const gpu = Array.from(host.querySelectorAll<HTMLInputElement>('.ml-fig-seg input')).find((i) => i.parentElement?.textContent === 'GPU (TornadoVM)')!;
    click(gpu);
    expect(host.querySelector('[data-box="trainerGpu"]')).not.toBeNull();
    expect(host.querySelector('[data-box="trainerCpu"]')).toBeNull();
    expect(q('.ml-fig-timeline__value').textContent).toBe('Training per generation: 41.8 s');
    key(q('.ml-fig-stepper'), 'Home');
    key(q('.ml-fig-stepper'), 'ArrowRight');
    key(q('.ml-fig-stepper'), 'ArrowRight');
    expect(Object.entries(states('data-box')).filter(([, v]) => v === 'active').map(([k]) => k).sort()).toEqual(['checkpoint', 'replayBuffer', 'sampleFiles', 'trainerGpu']);
  });

  it('V3: the run directory detail lists the eight files with their texts', async () => {
    await render(<ArchFigure catalog={arch} kind="pipeline" number={[1, 4]} />);
    click(q('[data-box="runDirectory"]'));
    const files = Array.from(host.querySelectorAll('.ml-fig-files li code')).map((c) => c.textContent);
    expect(files).toHaveLength(8);
    expect(files).toContain('gen-NNNN/decision.json');
  });

  it('shows no raw catalog key anywhere', async () => {
    for (const kind of ['offline', 'online', 'pipeline'] as const) {
      await render(<ArchFigure key={kind} catalog={arch} kind={kind} number={[1, 1]} />);
      key(q('.ml-fig-stepper'), 'End');
      expect(host.textContent).not.toMatch(/\bfig\.\w+\.|\bkit\.\w+\./);
    }
  });
});

describe('V4 (source tree)', () => {
  const rowIds = (): string[] => Array.from(host.querySelectorAll<HTMLElement>('[data-entry]')).map((r) => r.dataset.entry!);

  it('starts collapsed with one tab stop; the arrow keys move, Right expands, Left collapses', async () => {
    await render(<TreeFigure catalog={arch} number={[1, 1]} />);
    expect(q('[role="tree"]').getAttribute('aria-label')).toBe('Source tree');
    expect(host.querySelectorAll('[data-entry][tabindex="0"]')).toHaveLength(1);
    expect(rowIds()).not.toContain('tables');
    const settings = q('[data-entry="settings"]');
    settings.focus();
    key(settings, 'ArrowDown');
    expect(document.activeElement?.getAttribute('data-entry')).toBe('engine');
    key(document.activeElement!, 'End');
    expect(document.activeElement?.getAttribute('data-entry')).toBe('tornadoSdk');
    key(document.activeElement!, 'Home');
    const tablebase = q('[data-entry="tablebase"]');
    tablebase.focus();
    expect(tablebase.getAttribute('aria-expanded')).toBe('false');
    key(tablebase, 'ArrowRight');
    expect(rowIds()).toContain('tables');
    expect(q('[data-entry="tablebase"]').getAttribute('aria-expanded')).toBe('true');
    q('[data-entry="tablebase"]').focus();
    key(q('[data-entry="tablebase"]'), 'ArrowRight'); // open: moves to the first child
    expect(document.activeElement?.getAttribute('data-entry')).toBe('tables');
    expect(document.activeElement?.getAttribute('aria-level')).toBe('2');
    key(document.activeElement!, 'ArrowLeft'); // a child: moves to its parent
    expect(document.activeElement?.getAttribute('data-entry')).toBe('tablebase');
    key(document.activeElement!, 'ArrowLeft'); // an open parent: collapses
    expect(rowIds()).not.toContain('tables');
  });

  it('selects a module with Enter and shows what it depends on and what uses it; Enter again clears it', async () => {
    await render(<TreeFigure catalog={arch} number={[1, 1]} />);
    const search = q('[data-entry="search"]');
    search.focus();
    key(search, 'Enter');
    expect(q('[data-entry="search"]').getAttribute('aria-selected')).toBe('true');
    expect(q('.ml-fig-deps').textContent).toContain('engine, tablebase, nn');
    expect(q('.ml-fig-deps').textContent).toContain('selfplay');
    const active = Array.from(host.querySelectorAll('[data-module][data-state="active"]')).map((g) => g.getAttribute('data-module'));
    expect(active.sort()).toEqual(['app', 'engine', 'nn', 'search', 'selfplay', 'tablebase', 'teavm-net', 'teavm-web']);
    key(q('[data-entry="search"]'), 'Enter');
    expect(host.querySelector('[data-module][data-state="active"]')).toBeNull();
  });

  it('dims what does not run in the chosen place', async () => {
    await render(<TreeFigure catalog={arch} number={[1, 1]} />);
    const training = Array.from(host.querySelectorAll<HTMLInputElement>('.ml-fig-seg input')).find((i) => i.parentElement?.textContent === 'Training')!;
    click(training);
    expect(q('[data-entry="selfplay"]').getAttribute('data-dim')).toBeNull();
    expect(q('[data-entry="webApp"]').getAttribute('data-dim')).toBe('true');
  });

  it('marks generated entries with the tag and the Git LFS table after expanding', async () => {
    await render(<TreeFigure catalog={arch} number={[1, 1]} />);
    expect(q('[data-entry="data"]').textContent).toContain('git-ignored');
    click(q('[data-entry="tablebase"]'));
    expect(q('[data-entry="tables"]').textContent).toContain('Git LFS');
  });
});

describe('V5 (alpha-beta)', () => {
  it('counts 0 of 55 at the start, 55 of 55 after the last step and 210 of 210 in the minimax view', async () => {
    await render(<AlphaBetaFigure catalog={classic} number={[2, 1]} />);
    const counter = (): string => Array.from(host.querySelectorAll('.ml-fig-readout__cell')).find((c) => c.querySelector('dt')!.textContent === 'Nodes visited')!.querySelector('dd')!.textContent!;
    expect(counter()).toBe('0 of 55');
    key(q('.ml-fig-stepper'), 'End');
    expect(counter()).toBe('55 of 55');
    const minimax = Array.from(host.querySelectorAll<HTMLInputElement>('.ml-fig-seg input')).find((i) => i.parentElement?.textContent === 'Minimax (no pruning)')!;
    click(minimax);
    expect(counter()).toBe('210 of 210');
    expect(host.querySelector('.ml-fig-stepper')).toBeNull();
    expect(host.querySelectorAll('svg.ml-fig-ice .ml-fig-ice__cell--visited')).toHaveLength(196);
  });

  it('reads alpha, beta and score of the active call and says the cut-off', async () => {
    await render(<AlphaBetaFigure catalog={classic} number={[2, 1]} />);
    const value = (label: string): string => Array.from(host.querySelectorAll('.ml-fig-readout__cell')).find((c) => c.querySelector('dt')!.textContent === label)!.querySelector('dd')!.textContent!;
    key(q('.ml-fig-stepper'), 'Home');
    expect([value('Alpha'), value('Beta'), value('Score')]).toEqual(['−∞', '+∞', '−20']); // call 1 of the file: window (-inf, +inf), score -20
    // step 15 is the first root move of depth 2, entered with the full window
    for (let i = 1; i < 15; i++) forward();
    expect(status()).toContain('The search tries (2,-6)->(2,-4) with alpha −∞ and beta +∞');
    // the step of the first cut: last searched reply of the second root move of depth 2 (visit 31)
    for (let i = 15; i < 31; i++) forward();
    expect(status()).toContain('Cut-off');
    expect(host.querySelector('.ml-fig-ab__cut')?.textContent).toBe('Cut-off');
    expect(host.querySelectorAll('.ml-fig-ice__cut').length).toBe(1);
  });

  it('jumps to a root move with the select', async () => {
    await render(<AlphaBetaFigure catalog={classic} number={[2, 1]} />);
    const select = q<HTMLSelectElement>('select');
    expect(select.options).toHaveLength(15);
    await act(async () => {
      const set = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')!.set!;
      set.call(select, '1');
      select.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(q('.ml-fig-stepper__pos').textContent).toContain('30 / 55');
  });

  it('draws the root move and, for a reply, a dashed arrow on the board', async () => {
    await render(<AlphaBetaFigure catalog={classic} number={[2, 1]} />);
    expect(host.querySelectorAll('.ml-fig-board__arrow')).toHaveLength(0);
    for (let i = 0; i < 15; i++) forward();
    expect(host.querySelectorAll('.ml-fig-board__arrow')).toHaveLength(1);
    forward();
    const arrows = Array.from(host.querySelectorAll('.ml-fig-board__arrow'));
    expect(arrows).toHaveLength(2);
    expect(arrows.filter((a) => a.getAttribute('stroke-dasharray'))).toHaveLength(1);
  });
});

describe('V6 (evaluation)', () => {
  const readout = (label: string): string => Array.from(host.querySelectorAll('.ml-fig-readout__cell')).find((c) => c.querySelector('dt')!.textContent === label)!.querySelector('dd')!.textContent!;
  const box = (name: string): HTMLInputElement => q<HTMLInputElement>(`tr:has(.ml-fig-feat__pick[aria-label="Show ${name} on the board"]) input[type="checkbox"]`);

  it('shows the score +179 and the win probability 86 % with all 13 features at the learned weights', async () => {
    await render(<EvalFigure catalog={classic} number={[2, 3]} />);
    expect(host.querySelectorAll('tbody tr')).toHaveLength(13);
    expect(readout('Score')).toBe('+179');
    expect(readout('Win probability')).toBe('86 %');
  });

  it('recomputes when a feature is switched off: without Race × phase the score is +506', async () => {
    await render(<EvalFigure catalog={classic} number={[2, 3]} />);
    click(box('Race × phase'));
    expect(readout('Score')).toBe('+506');
    click(box('Race × phase'));
    expect(readout('Score')).toBe('+179');
  });

  it('selects a feature, explains it, marks it on the board and lets a weight change as a thought experiment', async () => {
    await render(<EvalFigure catalog={classic} number={[2, 3]} />);
    expect(q('.ml-fig-eval__explain').textContent).toContain('Select a feature');
    click(q('.ml-fig-feat__pick[aria-label="Show In the goal on the board"]'));
    expect(q('.ml-fig-eval__explain').textContent).toBe('Pieces that already stand in the goal corner.');
    expect(host.querySelectorAll('.ml-fig-board__nb')).toHaveLength(1);
    const slider = q<HTMLInputElement>('.ml-fig-eval__weight input[type="range"]');
    expect(slider.getAttribute('aria-valuetext')).toBe('0.29, learned 0.29');
    expect(host.querySelector('.ml-fig-tag')).toBeNull();
    setRange(slider, 0);
    // the weight of "in the goal" is 0.285 and its value 1: the score drops by 28.5 to 150 (178.645 - 28.531 = 150.114)
    expect(readout('Score')).toBe('+150');
    expect(q('.ml-fig-feat__pick[aria-label="Show In the goal on the board"]').textContent).toContain('changed');
    click(q('.ml-fig-eval__weight .ml-btn'));
    expect(readout('Score')).toBe('+179');
    expect(host.querySelector('.ml-fig-tag')).toBeNull();
  });

  it('draws the pairs of touching pieces as lines and the jumps as arrows', async () => {
    await render(<EvalFigure catalog={classic} number={[2, 3]} />);
    click(q('.ml-fig-feat__pick[aria-label="Show Touching pairs on the board"]'));
    expect(host.querySelectorAll('.ml-fig-board__pair')).toHaveLength(7);
    click(q('.ml-fig-feat__pick[aria-label="Show Forward jumps on the board"]'));
    expect(host.querySelectorAll('.ml-fig-board__pair')).toHaveLength(0);
    expect(host.querySelectorAll('.ml-fig-board__arrow')).toHaveLength(4);
  });
});

describe('V7 (budget)', () => {
  it('starts at the default level 3 and reads depth and move of both series for each of the five levels', async () => {
    await render(<BudgetFigure catalog={classic} number={[2, 2]} />);
    const slider = q<HTMLInputElement>('input[type="range"]');
    expect(slider.getAttribute('aria-valuetext')).toBe('20,000 nodes, depth 6');
    expect(q('.ml-fig-budget__readout').textContent).toContain('20,000 nodes: depth 6, move (4,-5)->(3,-5)');
    expect(q('.ml-fig-budget__readout').textContent).toContain('Without enhancements: depth 3, move (1,-4)->(1,-2)');
    setRange(slider, 5);
    expect(slider.getAttribute('aria-valuetext')).toBe('500,000 nodes, depth 10');
    expect(q('.ml-fig-budget__readout').textContent).toContain('500,000 nodes: depth 10, move (4,-7)->(4,-6)');
    setRange(slider, 1);
    expect(q('.ml-fig-budget__readout').textContent).toContain('1,000 nodes: depth 3');
    expect(q('.ml-fig-budget__readout').textContent).toContain('Without enhancements: depth 2');
  });

  it('draws five points per series on a log axis and names the plot', async () => {
    await render(<BudgetFigure catalog={classic} number={[2, 2]} />);
    expect(host.querySelectorAll('svg.ml-fig-plot .ml-fig-plot__pt--1')).toHaveLength(5);
    expect(host.querySelectorAll('svg.ml-fig-plot .ml-fig-plot__pt--2')).toHaveLength(5);
    expect(q('svg.ml-fig-plot title').textContent).toBe('Node budget and search depth');
    expect(q('svg.ml-fig-plot desc').textContent).toContain('1,000 to 500,000');
  });
});
