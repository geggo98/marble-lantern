// @vitest-environment jsdom
// The kit components in jsdom: role, accessible name, title and desc, focus order, keyboard (design notes, sections 6, 8 and 9).
// There is no browser layout here, so layout-dependent parts get an explicit `layout` or a stubbed width.
import { act, useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BoxDiagram, Chip, Figure, FigureDetail, HeatBoard, Manipulate, MiniBoard, Plot, PlotBars, PlotDots, PlotLine, PlotRef, ReadingList, Segmented, Slider, Stepper, IcicleTree, boxDiagramLabels, kitEn, layoutBoxes, stepperLabels, useStepper } from './index';
import type { ArchData, CellPicker } from './index';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

import archJson from '../data/arch-offline.json';

const arch = archJson as ArchData;

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
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const render = async (node: React.JSX.Element): Promise<void> => {
  await act(async () => root.render(node));
};

const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};

const text = (id: string): string => (host.ownerDocument.getElementById(id)?.textContent ?? '');
const namedBy = (el: Element): string =>
  (el.getAttribute('aria-labelledby') ?? '')
    .split(' ')
    .filter(Boolean)
    .map(text)
    .join(' | ');

const key = (el: Element, k: string): void => {
  act(() => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  });
};
const click = (el: Element): void => {
  act(() => {
    (el as HTMLElement).click();
  });
};

/** Elements that a Tab key stops at, in document order, with the name a screen reader would give them. */
function tabStops(): string[] {
  const all = [...host.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]')];
  return all
    .filter((el) => el.getAttribute('tabindex') !== '-1')
    .map((el) => el.getAttribute('aria-label') ?? (el as HTMLInputElement).labels?.[0]?.textContent ?? el.closest('label')?.textContent ?? el.textContent ?? '')
    .map((s) => s.trim());
}

const stubMotion = (reduce: boolean): void => {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('prefers-reduced-motion'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined }));
};

const labels = stepperLabels(kitEn, 'One move');

describe('Figure', () => {
  it('is a figure named by its title, with the narration as its only live region and the caption as figcaption', async () => {
    await render(
      <Figure id="fig-t" labelWord="Fig." number={3} title="A title" status={{ step: 'Step 1 of 2', text: 'It starts.' }} caption="What it shows." foot={<Chip prefix="Data file">x.json</Chip>}>
        <p>stage</p>
      </Figure>,
    );
    const fig = q('figure.ml-fig');
    expect(namedBy(fig)).toBe('A title');
    expect(q('h3').id).toBe('fig-t-title');
    expect(q('.ml-fig__label').textContent).toBe('Fig. 3');
    const live = host.querySelectorAll('[aria-live]');
    expect(live).toHaveLength(1);
    expect(live[0]?.getAttribute('role')).toBe('status');
    expect(live[0]?.textContent).toBe('Step 1 of 2It starts.');
    expect(q('figcaption .ml-fig__caption').textContent).toBe('What it shows.');
    expect(q('.ml-fig__foot .ml-visually-hidden').textContent).toBe('Data file ');
  });

  it('writes the narration into the live region that was there before the step', async () => {
    function Demo(): React.JSX.Element {
      const [n, setN] = useState(0);
      return (
        <Figure id="f" title="T" status={{ text: `now ${n}` }}>
          <button type="button" onClick={() => setN(n + 1)}>
            go
          </button>
        </Figure>
      );
    }
    await render(<Demo />);
    const region = q('[role="status"]');
    click(q('button'));
    expect(q('[role="status"]')).toBe(region);
    expect(region.textContent).toBe('now 1');
  });
});

describe('Plot', () => {
  const axes = { x: { scale: 'log', domain: [700, 700000], ticks: [1000, 5000, 20000, 100000, 500000], label: 'Nodes' } as const, y: { scale: 'linear', domain: [0, 11], ticks: [0, 2, 4, 6, 8, 10], label: 'Depth' } as const };

  it('is an image with a title and a description, and uses the wide view box when it is told to', async () => {
    await render(
      <Plot title="Depth by budget" desc="Depth grows with the node budget." layout="wide" {...axes}>
        <PlotLine points={[{ x: 1000, y: 3 }, { x: 5000, y: 5 }]} />
      </Plot>,
    );
    const svg = q('svg.ml-fig-plot');
    expect(svg.getAttribute('role')).toBe('img');
    expect(namedBy(svg)).toBe('Depth by budget | Depth grows with the node budget.');
    expect(svg.getAttribute('viewBox')).toBe('0 0 640 280');
    expect(q('svg title').tagName.toLowerCase()).toBe('title');
  });

  it('draws the compact view box, labelled ticks on both axes and minor ticks on a log axis', async () => {
    await render(
      <Plot title="t" desc="d" layout="compact" {...axes}>
        <PlotRef y={5} label="five" />
      </Plot>,
    );
    expect(q('svg').getAttribute('viewBox')).toBe('0 0 260 180');
    const labels = [...host.querySelectorAll('svg text')].map((t) => t.textContent);
    expect(labels).toEqual(expect.arrayContaining(['1k', '5k', '20k', '100k', '500k', '0', '2', '10', 'Nodes', 'Depth', 'five']));
    expect(host.querySelectorAll('.ml-fig-plot__tick--minor').length).toBeGreaterThan(5);
    // the y label stands above the frame's top left corner, horizontally (no rotation)
    const yLabel = [...host.querySelectorAll('text.ml-fig-plot__axislabel')].find((t) => t.textContent === 'Depth');
    expect(yLabel?.getAttribute('transform')).toBeNull();
    expect(Number(yLabel?.getAttribute('y'))).toBeLessThan(28);
  });

  it('chooses the view box from the measured width: 639 px compact, 640 px wide', async () => {
    let width = 639;
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width, height: 100, x: 0, y: 0, top: 0, left: 0, right: width, bottom: 100, toJSON: () => ({}) }));
    await render(<Plot title="t" desc="d" {...axes} />);
    expect(q('svg').getAttribute('data-layout')).toBe('compact');
    width = 640;
    await act(async () => root.unmount());
    root = createRoot(host);
    await render(<Plot title="t" desc="d" {...axes} />);
    expect(q('svg').getAttribute('data-layout')).toBe('wide');
  });

  it('draws bars from the baseline, dots with leaders, whiskers and a value label', async () => {
    await render(
      <Plot title="t" desc="d" layout="wide" x={{ scale: 'linear', domain: [0, 10] }} y={{ scale: 'linear', domain: [0, 10] }}>
        <PlotBars bars={[{ x: 5, y: 10 }]} />
        <PlotDots dots={[{ x: 2, y: 4, lo: 2, hi: 6, valueLabel: '4.0', best: true }]} leaders />
      </Plot>,
    );
    const bar = q('rect.ml-fig-plot__bar');
    expect(Number(bar.getAttribute('y'))).toBe(28); // the top of the frame at y = 10
    expect(Number(bar.getAttribute('height'))).toBe(280 - 44 - 28); // down to the bottom of the frame
    expect(host.querySelectorAll('.ml-fig-plot__leader')).toHaveLength(1);
    expect(host.querySelectorAll('.ml-fig-plot__whisker')).toHaveLength(1);
    expect(q('text.ml-fig-plot__best').textContent).toBe('4.0');
  });

  it('refuses bars on a log axis, because a log axis has no zero', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    await expect(render(<Plot title="t" desc="d" layout="wide" x={{ scale: 'linear', domain: [0, 10] }} y={{ scale: 'log', domain: [1, 100] }}><PlotBars bars={[{ x: 1, y: 10 }]} /></Plot>)).rejects.toThrow(/linear axis/);
  });

  it('puts category names under the axis', async () => {
    await render(<Plot title="t" desc="d" layout="compact" x={{ scale: 'category', categories: ['A', 'B', 'C'] }} y={{ scale: 'linear', domain: [0, 1] }} />);
    expect([...host.querySelectorAll('.ml-fig-plot__catlabel')].map((t) => t.textContent)).toEqual(['A', 'B', 'C']);
  });
});

describe('boards', () => {
  it('MiniBoard: an image with title and description, 121 sockets, marbles as uses of the sprite', async () => {
    await render(<MiniBoard title="Start" desc="The start position." pieces={{ a: [0, 1, 2], b: [118, 119] }} rings={[5]} arrows={[{ from: 0, to: 5 }]} />);
    const svg = q('svg.ml-fig-board');
    expect(svg.getAttribute('role')).toBe('img');
    expect(namedBy(svg)).toBe('Start | The start position.');
    expect(svg.getAttribute('viewBox')).toBe('-234 -266 468 532');
    expect(host.querySelectorAll('circle.ml-socket')).toHaveLength(121);
    expect(q('circle.ml-socket').getAttribute('r')).toBe('11.5'); // socket radius of the game board (tokens: --ml-board-socket-r)
    expect([...host.querySelectorAll('use')].map((u) => u.getAttribute('href'))).toEqual(['#ml-piece-a', '#ml-piece-a', '#ml-piece-a', '#ml-piece-b', '#ml-piece-b']);
    expect(host.querySelectorAll('.ml-fig-board__nb')).toHaveLength(1);
    expect(host.querySelectorAll('.ml-fig-board__arrowhead')).toHaveLength(1);
    expect(host.querySelector('text')).toBeNull(); // a board holds no text
  });

  it('HeatBoard: a disc per valued cell with a rising radius, a diamond for a negative signed value', async () => {
    const cells = Array.from({ length: 121 }, () => null) as ({ level: 1 | 5 } | { signed: 'neg-2' | 'pos-1' } | null)[];
    cells[10] = { level: 1 };
    cells[11] = { level: 5 };
    cells[12] = { signed: 'neg-2' };
    cells[13] = { signed: 'pos-1' };
    await render(<HeatBoard title="Heat" desc="d" cells={cells} />);
    expect(host.querySelectorAll('circle.ml-fig-heat__ring')).toHaveLength(121);
    const r = (level: number): number => Number(q(`[data-level="${level}"]`).getAttribute('r'));
    expect(r(1)).toBeLessThan(r(5));
    expect(q('[data-signed="neg-2"]').tagName.toLowerCase()).toBe('path');
    expect(q('[data-signed="pos-1"]').tagName.toLowerCase()).toBe('circle');
  });

  it('a board with a picker is a group with one tab stop; the arrow keys move the selection by the geometry of the game board', async () => {
    function Demo(): React.JSX.Element {
      const [cell, setCell] = useState(60);
      const picker: CellPicker = { value: cell, onChange: setCell, label: 'Pick a cell', cellLabel: (c) => `cell ${c}` };
      return <MiniBoard title="B" desc="d" pieces={{}} picker={picker} />;
    }
    await render(<Demo />);
    const svg = q('svg.ml-fig-board');
    expect(svg.getAttribute('role')).toBe('group');
    expect(q('[role="group"][aria-label="Pick a cell"]')).toBeTruthy();
    const stops = () => [...host.querySelectorAll('[data-cell][tabindex="0"]')].map((e) => e.getAttribute('data-cell'));
    expect(stops()).toEqual(['60']);
    const here = q('[data-cell="60"][role="button"]');
    act(() => here.focus());
    key(here, 'ArrowRight');
    expect(stops()).toEqual(['61']); // the next cell in the row (cell ids run along a row)
    expect(document.activeElement?.getAttribute('data-cell')).toBe('61');
    key(document.activeElement as Element, 'ArrowLeft');
    expect(stops()).toEqual(['60']);
    key(document.activeElement as Element, 'Home');
    expect(Number(stops()[0])).toBeLessThan(60);
    expect(q('[aria-pressed="true"]').getAttribute('aria-label')).toBe(`cell ${stops()[0]}`);
  });
});

describe('Stepper', () => {
  function Demo({ count = 6 }: { count?: number }): React.JSX.Element {
    const { state, dispatch } = useStepper(count, 1200);
    return (
      <Manipulate>
        <Stepper state={state} dispatch={dispatch} labels={labels} />
      </Manipulate>
    );
  }
  const pos = (): string => q('.ml-fig-stepper__pos').textContent ?? '';
  const button = (name: string): HTMLElement => q(`button[aria-label="${name}"]`);

  it('is a group of four named buttons and the position as text', async () => {
    await render(<Demo />);
    const group = q('[role="group"].ml-fig-stepper');
    expect(group.getAttribute('aria-label')).toBe('One move');
    expect([...group.querySelectorAll('button')].map((b) => b.getAttribute('aria-label'))).toEqual(['Back to the start', 'One step back', 'Play', 'One step forward']);
    expect(pos()).toBe('Step 0 / 6');
  });

  it('Left and Right step and keep the focus on the button; Home and End jump; the page is not scrolled (default prevented)', async () => {
    await render(<Demo />);
    const fwd = button('One step forward');
    act(() => fwd.focus());
    const event = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true });
    act(() => {
      fwd.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(true);
    expect(pos()).toBe('Step 1 / 6');
    expect(document.activeElement).toBe(fwd);
    key(fwd, 'End');
    expect(pos()).toBe('Step 6 / 6');
    key(fwd, 'ArrowLeft');
    expect(pos()).toBe('Step 5 / 6');
    key(fwd, 'Home');
    expect(pos()).toBe('Step 1 / 6');
    expect(document.activeElement).toBe(fwd);
  });

  it('ignores other keys without preventing them', async () => {
    await render(<Demo />);
    const fwd = button('One step forward');
    const event = new KeyboardEvent('keydown', { key: 'a', bubbles: true, cancelable: true });
    act(() => {
      fwd.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(false);
    expect(pos()).toBe('Step 0 / 6');
  });

  it('keeps the focus on a button at its bound: aria-disabled, never disabled', async () => {
    await render(<Demo count={2} />);
    const fwd = button('One step forward');
    act(() => fwd.focus());
    key(fwd, 'End');
    expect(fwd.getAttribute('aria-disabled')).toBe('true');
    expect(fwd.hasAttribute('disabled')).toBe(false);
    expect(document.activeElement).toBe(fwd);
    click(fwd);
    expect(pos()).toBe('Step 2 / 2');
  });

  it('plays one step per 1.2 s, stops at the last step, and a key press stops it earlier', async () => {
    vi.useFakeTimers();
    await render(<Demo count={3} />);
    click(button('Play'));
    expect(button('Pause')).toBeTruthy();
    await act(async () => {
      vi.advanceTimersByTime(1199);
    });
    expect(pos()).toBe('Step 0 / 3');
    await act(async () => {
      vi.advanceTimersByTime(1);
    });
    expect(pos()).toBe('Step 1 / 3');
    key(button('Pause'), 'ArrowRight'); // a manual step stops the playback
    expect(pos()).toBe('Step 2 / 3');
    expect(button('Play')).toBeTruthy();
    click(button('Play'));
    await act(async () => {
      vi.advanceTimersByTime(5000);
    });
    expect(pos()).toBe('Step 3 / 3');
    expect(button('Play')).toBeTruthy(); // not looping
  });

  it('under reduced motion there is no play button, and back and forward still work', async () => {
    stubMotion(true);
    await render(<Demo />);
    expect([...host.querySelectorAll('.ml-fig-stepper button')].map((b) => b.getAttribute('aria-label'))).toEqual(['Back to the start', 'One step back', 'One step forward']);
    click(button('One step forward'));
    expect(pos()).toBe('Step 1 / 6');
  });

  it('draws one segment per step up to 12 steps and a progress bar beyond', async () => {
    await render(<Demo count={12} />);
    expect(host.querySelectorAll('.ml-fig-stepper__seg')).toHaveLength(12);
    await render(<Demo count={55} />);
    expect(host.querySelectorAll('.ml-fig-stepper__seg')).toHaveLength(0);
    expect(host.querySelector('.ml-progress')).toBeTruthy();
  });
});

describe('Manipulate controls', () => {
  it('a slider has a label, a spoken value and a fill; a segmented switch is a labelled radiogroup', async () => {
    await render(
      <Manipulate>
        <Slider label="Node budget" min={0} max={4} value={2} onChange={() => undefined} valueText="20,000 nodes, depth 6" out="20k" scale={['1k', '500k']} />
        <Segmented label="Search" options={[{ value: 'a', label: 'Minimax' }, { value: 'b', label: 'Alpha-beta' }]} value="b" onChange={() => undefined} />
      </Manipulate>,
    );
    const input = q<HTMLInputElement>('input[type="range"]');
    expect(host.querySelector(`label[for="${input.id}"]`)?.textContent).toBe('Node budget');
    expect(input.getAttribute('aria-valuetext')).toBe('20,000 nodes, depth 6');
    expect(input.style.getPropertyValue('--ml-slider-fill')).toBe('50%');
    const group = q('[role="radiogroup"]');
    expect(namedBy(group)).toBe('Search');
    expect([...group.querySelectorAll<HTMLInputElement>('input')].map((i) => i.checked)).toEqual([false, true]);
  });
});

describe('BoxDiagram', () => {
  const base = { data: arch, labels: boxDiagramLabels(kitEn, 'Offline architecture', 'Edges, in order') };

  it('names every box with its label, and the diagram as a group', async () => {
    await render(<BoxDiagram {...base} layout="compact" />);
    expect(q('[role="group"].ml-fig-box').getAttribute('aria-label')).toBe('Offline architecture');
    const names = [...host.querySelectorAll('button.ml-fig-box__node')].map((b) => b.textContent);
    expect([...names].sort()).toEqual(arch.boxes.map((b) => b.label).sort());
    expect(q('svg.ml-fig-box__edges').getAttribute('aria-hidden')).toBe('true');
  });

  it('compact: numbered markers in the diagram and a visible numbered list that names kind, source, target and label', async () => {
    await render(<BoxDiagram {...base} layout="compact" />);
    expect(host.querySelectorAll('.ml-fig-box__num')).toHaveLength(arch.edges.length);
    const list = q('ol.ml-fig-box__key');
    expect(list.getAttribute('aria-label')).toBe('Edges, in order');
    expect(list.className).not.toContain('--hidden');
    const rows = [...list.querySelectorAll('li')];
    expect(rows).toHaveLength(arch.edges.length);
    expect(rows[0]?.textContent).toBe('1Control: Browser page (React) → Board (SVG): renders'.replace('1', '1'));
    expect(host.querySelectorAll('.ml-fig-num--control').length).toBeGreaterThan(0);
    // a control marker is a diamond (its own class), a data marker is a disc
    const kinds = arch.edges.map((e) => e.kind);
    const markers = [...host.querySelectorAll('.ml-fig-box__num')].map((m) => (m.classList.contains('ml-fig-num--control') ? 'control' : 'data'));
    expect(markers).toEqual(kinds);
  });

  it('wide: edge labels in the diagram and the same list for screen readers only', async () => {
    await render(<BoxDiagram {...base} layout="wide" />);
    expect(host.querySelectorAll('.ml-fig-box__num')).toHaveLength(0);
    expect(host.querySelectorAll('.ml-fig-box__edgelabel')).toHaveLength(arch.edges.length);
    expect(q('ol.ml-fig-box__key').className).toContain('ml-fig-box__key--hidden');
    expect(host.querySelectorAll('ol.ml-fig-box__key li')).toHaveLength(arch.edges.length);
  });

  it('draws a data edge solid with a filled head and a control edge dashed with an open head', async () => {
    await render(<BoxDiagram {...base} layout="wide" />);
    const first = q('[data-edge="0"]'); // page -> board, control
    expect(first.getAttribute('data-kind')).toBe('control');
    expect(first.querySelector('path.ml-fig-edge--control')).toBeTruthy();
    expect(first.querySelector('path.ml-fig-edge__head--control')?.getAttribute('d')).not.toContain('Z');
    const data = q('[data-edge="1"]'); // page -> gameUrl, data
    expect(data.querySelector('path.ml-fig-edge--control')).toBeNull();
    expect(data.querySelector('path.ml-fig-edge__head--data')?.getAttribute('d')).toMatch(/Z$/);
  });

  it('idle: nothing has a state; with a step, the rest is dim; the flow switch dims the other kind, it does not remove it', async () => {
    await render(<BoxDiagram {...base} layout="wide" />);
    expect(host.querySelectorAll('[data-state]')).toHaveLength(0);
    await render(<BoxDiagram {...base} layout="wide" activeBoxes={['page', 'board']} activeEdges={[0]} />);
    expect(q('[data-box="page"]').getAttribute('data-state')).toBe('active');
    expect(q('[data-box="controller"]').getAttribute('data-state')).toBe('dim');
    expect(q('[data-edge="0"] path.ml-fig-edge').getAttribute('data-state')).toBe('active');
    expect(q('[data-edge="1"] path.ml-fig-edge').getAttribute('data-state')).toBe('dim');
    await render(<BoxDiagram {...base} layout="wide" flow="control" />);
    expect(q('[data-edge="0"] path.ml-fig-edge').getAttribute('data-state')).toBeNull(); // control edge, shown
    expect(q('[data-edge="1"] path.ml-fig-edge').getAttribute('data-state')).toBe('dim'); // data edge, dimmed but there
    expect(host.querySelectorAll('path.ml-fig-edge')).toHaveLength(arch.edges.length);
  });

  it('the active edge is never dashed unless it is a control edge, and carries the travelling dot only when motion is allowed', async () => {
    await render(<BoxDiagram {...base} layout="wide" activeBoxes={['page']} activeEdges={[1]} />);
    const active = q('[data-edge="1"] path.ml-fig-edge');
    expect(active.getAttribute('data-state')).toBe('active');
    expect(active.getAttribute('class')).toBe('ml-fig-edge');
    expect(host.querySelectorAll('.ml-fig-edge__dot')).toHaveLength(1);
    stubMotion(true);
    await render(<BoxDiagram {...base} layout="wide" activeBoxes={['page']} activeEdges={[1]} />);
    expect(host.querySelectorAll('.ml-fig-edge__dot')).toHaveLength(0);
  });

  it('chooses the layout from the figure width when it is not told', async () => {
    let width = 639;
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => ({ width, height: 100, x: 0, y: 0, top: 0, left: 0, right: width, bottom: 100, toJSON: () => ({}) }));
    await render(<BoxDiagram {...base} />);
    expect(q('.ml-fig-boxwrap').getAttribute('data-layout')).toBe('compact');
    width = 640;
    await act(async () => root.unmount());
    root = createRoot(host);
    await render(<BoxDiagram {...base} />);
    expect(q('.ml-fig-boxwrap').getAttribute('data-layout')).toBe('wide');
  });

  it('the boxes toggle their detail panel; Escape and the close button close it and the focus returns to the box', async () => {
    function Demo(): React.JSX.Element {
      const [open, setOpen] = useState<string | null>(null);
      return (
        <Figure
          id="f"
          title="Offline"
          panel={<Manipulate><Slider label="Speed" min={0} max={4} value={1} onChange={() => undefined} /></Manipulate>}
          detail={open ? <FigureDetail id="detail" title={`Details of ${open}`} closeLabel="Close details" onClose={() => setOpen(null)}>text</FigureDetail> : null}
        >
          <BoxDiagram {...base} layout="compact" openId={open} onOpen={setOpen} detailId="detail" />
        </Figure>
      );
    }
    await render(<Demo />);
    const box = q('[data-box="board"]');
    expect(box.getAttribute('aria-expanded')).toBe('false');
    expect(box.getAttribute('aria-controls')).toBe('detail');
    act(() => box.focus());
    click(box);
    expect(box.getAttribute('aria-expanded')).toBe('true');
    const detail = q('section.ml-fig__detail');
    expect(namedBy(detail)).toBe('Details of board');
    expect(detail.id).toBe('detail');
    // Escape from the box
    key(box, 'Escape');
    expect(host.querySelector('section.ml-fig__detail')).toBeNull();
    expect(document.activeElement).toBe(box);
    // Escape from inside the detail panel, and the close button
    click(box);
    act(() => q('section.ml-fig__detail button').focus());
    key(q('section.ml-fig__detail button'), 'Escape');
    expect(host.querySelector('section.ml-fig__detail')).toBeNull();
    expect(document.activeElement).toBe(box);
    click(box);
    act(() => q('section.ml-fig__detail button').focus());
    click(q('section.ml-fig__detail button'));
    expect(document.activeElement).toBe(box);
    click(box);
    click(box); // a second click on the open box closes it
    expect(box.getAttribute('aria-expanded')).toBe('false');
  });

  it('focus order follows the DOM: panel controls, then the boxes row by row, then the close button of the detail panel', async () => {
    function Demo(): React.JSX.Element {
      const { state, dispatch } = useStepper(6);
      return (
        <Figure
          id="f"
          title="T"
          panel={
            <Manipulate>
              <Slider label="Speed" min={0} max={4} value={1} onChange={() => undefined} />
              <Stepper state={state} dispatch={dispatch} labels={labels} />
            </Manipulate>
          }
          detail={<FigureDetail title="D" closeLabel="Close details" onClose={() => undefined}>x</FigureDetail>}
          foot={<Chip href="https://example.invalid/x" prefix="Source file">a.ts</Chip>}
        >
          <BoxDiagram {...base} layout="compact" openId="page" onOpen={() => undefined} />
        </Figure>
      );
    }
    await render(<Demo />);
    const stops = tabStops();
    expect(stops.slice(0, 5)).toEqual(['Speed', 'Back to the start', 'One step back', 'Play', 'One step forward']);
    const boxes = stops.slice(5, 5 + arch.boxes.length);
    const rowByRow = [...layoutBoxes(arch, 'compact').nodes].sort((a, b) => a.y - b.y || a.x - b.x).map((n) => arch.boxes.find((b) => b.id === n.id)?.label);
    expect(boxes).toEqual(rowByRow);
    expect(stops.slice(5 + arch.boxes.length)).toEqual(['Close details', 'Source file a.ts']);
  });
});

describe('IcicleTree', () => {
  it('is an image with title and description; draws a move bar and cells per column and a cut line', async () => {
    await render(
      <IcicleTree
        title="Tree"
        desc="d"
        layout="wide"
        rootText="alpha beta"
        columns={[
          { glyph: '»', cells: ['visited', 'visited', 'active'] },
          { glyph: '›', cells: ['visited', 'skipped', 'todo'], cutAfter: 1 },
        ]}
      />,
    );
    const svg = q('svg.ml-fig-ice');
    expect(svg.getAttribute('role')).toBe('img');
    expect(namedBy(svg)).toBe('Tree | d');
    expect(host.querySelectorAll('.ml-fig-ice__move')).toHaveLength(2);
    expect(host.querySelectorAll('.ml-fig-ice__cell')).toHaveLength(6);
    expect(host.querySelectorAll('.ml-fig-ice__cell--active')).toHaveLength(1);
    expect(host.querySelectorAll('.ml-fig-ice__cut')).toHaveLength(1);
    expect([...host.querySelectorAll('text')].map((t) => t.textContent)).toEqual(['alpha beta', '»', '›']);
  });

  it('has the view boxes of the notes for 14 columns of 14 replies: 640 x 218 wide, 260 x 204 compact', async () => {
    const columns = Array.from({ length: 14 }, () => ({ cells: Array.from({ length: 14 }, () => 'visited' as const) }));
    await render(<IcicleTree title="t" desc="d" layout="wide" columns={columns} />);
    expect(q('svg').getAttribute('viewBox')).toBe('0 0 640 218');
    await render(<IcicleTree title="t" desc="d" layout="compact" columns={columns} />);
    expect(q('svg').getAttribute('viewBox')).toBe('0 0 260 204');
  });
});

describe('ReadingList', () => {
  it('is an ordered list of external links with level and paywall tags, and the heading as given', async () => {
    await render(
      <ReadingList
        title="Further reading"
        levelLabels={{ beginner: 'Introduction', advanced: 'Advanced' }}
        paywalled="may need a subscription"
        items={[
          { id: 1, title: 'Minimax', authors: 'A. Author', url: 'https://example.invalid/m', level: 'beginner', note: 'A first look.' },
          { id: 2, title: 'Bandits', year: 2006, url: 'https://example.invalid/b', level: 'advanced', paywalled: true },
        ]}
      />,
    );
    expect(q('h2').textContent).toBe('Further reading');
    const rows = [...host.querySelectorAll('ol > li')];
    expect(rows).toHaveLength(2);
    expect(rows[0]?.querySelector('a')?.getAttribute('href')).toBe('https://example.invalid/m');
    expect(rows[0]?.querySelector('.ml-tag')?.textContent).toBe('Introduction');
    expect(rows[0]?.textContent).toContain('A first look.');
    expect([...(rows[1]?.querySelectorAll('.ml-tag') ?? [])].map((t) => t.textContent)).toEqual(['Advanced', 'may need a subscription']);
    expect(rows[1]?.textContent).toContain('2006');
  });
});
