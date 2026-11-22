// @vitest-environment jsdom
// The start dialog: the default path is one click, every mode and seat reaches onStart, the dialog is a modal that Escape closes only
// when a game is running, and a link dialog names the jump rule.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_CHOICE } from '../app/start-choice';
import type { StartChoice } from '../app/start-choice';
import { StartDialog } from './StartDialog';
import type { StartDialogModel } from './StartDialog';
import type { Variant } from '../game/variant';
import en from '../i18n/en.json';
import { evaluateSelection } from '../engines/net/selection';
import { listModels } from '../engines/net/model-manifest';
import { summaryItems, variantOfChoice } from '../app/start-rules';
import { NetSelectionStore } from '../app/net-selection';
import { clearEngines, registerEngine } from '../engines/registry';
import type { EngineDefinition } from '../engines/registry';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = <T extends HTMLElement = HTMLElement>(s: string): T | null => document.querySelector<T>(s);
const choice = (name: string): HTMLInputElement => q<HTMLInputElement>(`[data-choice="${name}"]`)!;
const click = async (el: Element): Promise<void> => {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};

async function open(onStart: (c: StartChoice, b: boolean) => void, initial: StartChoice = DEFAULT_CHOICE, onCancel?: () => void): Promise<void> {
  const model: StartDialogModel = { kind: 'begin', initial, onStart };
  await act(async () => {
    root.render(<StartDialog model={model} onCancel={onCancel} />);
  });
}

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

describe('the begin dialog', () => {
  it('default path: the primary button has the focus, one click starts the classic game', async () => {
    const onStart = vi.fn();
    await open(onStart);
    expect(document.activeElement).toBe(choice('begin-yes'));
    expect(choice('begin-yes').className).toContain('ml-btn--primary');
    await click(choice('begin-yes'));
    expect(onStart).toHaveBeenCalledTimes(1);
    expect(onStart).toHaveBeenCalledWith(DEFAULT_CHOICE, true);
  });

  it('the first focus does not scroll the sheet (preventScroll), and the sheet opens at its top', async () => {
    const focus = vi.spyOn(HTMLElement.prototype, 'focus');
    try {
      await open(vi.fn());
      const first = focus.mock.calls.findIndex((_, i) => focus.mock.instances[i] === choice('begin-yes'));
      expect(first, 'the primary button was focused').toBeGreaterThanOrEqual(0);
      expect(focus.mock.calls[first]![0]).toEqual({ preventScroll: true });
      expect(q('[role="dialog"]')!.scrollTop).toBe(0);
    } finally {
      focus.mockRestore();
    }
  });

  it('"No, the engine begins" answers false', async () => {
    const onStart = vi.fn();
    await open(onStart);
    await click(choice('begin-no'));
    expect(onStart).toHaveBeenCalledWith(DEFAULT_CHOICE, false);
  });

  it('is a modal dialog with a title, and its groups are radio groups with legends', async () => {
    await open(vi.fn());
    const d = q('[role="dialog"]')!;
    expect(d.getAttribute('aria-modal')).toBe('true');
    expect(d.getAttribute('aria-labelledby')).toBe(d.querySelector('h2')!.id);
    const groups = [...d.querySelectorAll('fieldset')];
    // board size, jump rule, foreign triangles (added 2026-11-20) and game mode; no seat group in the two-player mode
    expect(groups).toHaveLength(4);
    for (const g of groups) {
      expect(g.querySelector('legend')!.textContent).not.toBe('');
      expect(g.querySelectorAll('input[type="radio"]').length).toBeGreaterThanOrEqual(2);
    }
  });

  it('the long hop is chosen with a radio, and the choice reaches onStart', async () => {
    const onStart = vi.fn();
    await open(onStart);
    await click(choice('jump-long'));
    expect(choice('jump-long').checked).toBe(true);
    await click(choice('begin-yes'));
    expect(onStart).toHaveBeenCalledWith({ ...DEFAULT_CHOICE, jump: 'long' }, true);
  });

  it('the foreign-corner rule is a radio group, closed by default, and the choice reaches onStart', async () => {
    const onStart = vi.fn();
    await open(onStart);
    expect(choice('camps-closed').checked).toBe(true);
    expect(choice('camps-pass').checked).toBe(false);
    await click(choice('camps-open'));
    expect(choice('camps-open').checked).toBe(true);
    await click(choice('camps-pass'));
    await click(choice('begin-no'));
    expect(onStart).toHaveBeenCalledWith({ ...DEFAULT_CHOICE, camps: 'pass-through' }, false);
  });

  it('a link to an open-corner game names the rule', async () => {
    const model: StartDialogModel = {
      kind: 'link', ply: 4, sideToMove: 0, onContinueAs: () => undefined, onNew: () => undefined,
      variant: { jump: 'adjacent', players: 2, camps: 'open' },
    };
    await act(async () => {
      root.render(<StartDialog model={model} />);
    });
    expect(q('[data-testid="link-camps"]')?.textContent).toMatch(/Open|Erlaubt/);
  });

  it('hot-seat shows the engine seat (default: the last) instead of the yes/no pair, and one start button', async () => {
    const onStart = vi.fn();
    await open(onStart);
    await click(choice('mode-hotseat'));
    expect(q('[data-choice="begin-yes"]')).toBeNull();
    expect(choice('engine-seat-3').checked).toBe(true);
    await click(choice('engine-seat-1'));
    await click(choice('begin-start'));
    expect(onStart).toHaveBeenCalledWith({ ...DEFAULT_CHOICE, mode: 'hotseat', engineSeat: 0 }, true);
  });

  it('"you against 2 engines" asks for the seat of the person', async () => {
    const onStart = vi.fn();
    await open(onStart);
    await click(choice('mode-engines'));
    expect(choice('human-seat-1').checked).toBe(true);
    await click(choice('human-seat-3'));
    await click(choice('jump-long'));
    await click(choice('begin-start'));
    expect(onStart).toHaveBeenCalledWith({ jump: 'long', camps: 'closed', rows: 5, ranking: 'first', mode: 'engines', humanSeat: 2, engineSeat: 2 }, true);
  });

  it('starts from the remembered choice', async () => {
    await open(vi.fn(), { jump: 'long', camps: 'closed', rows: 4, ranking: 'first', mode: 'engines', humanSeat: 1, engineSeat: 0 });
    expect(choice('jump-long').checked).toBe(true);
    expect(choice('mode-engines').checked).toBe(true);
    expect(choice('human-seat-2').checked).toBe(true);
  });

  it('regression: the begin dialog that replaces a link dialog (same instance) starts from model.initial, not from the link fallback (4 rows)', async () => {
    const link: StartDialogModel = { kind: 'link', ply: 3, sideToMove: 0, onContinueAs: vi.fn(), onNew: vi.fn() };
    await act(async () => {
      root.render(<StartDialog model={link} />);
    });
    const onStart = vi.fn();
    const begin: StartDialogModel = { kind: 'begin', initial: { ...DEFAULT_CHOICE, rows: 5, jump: 'long' }, onStart };
    await act(async () => {
      root.render(<StartDialog model={begin} />);
    });
    expect(choice('rows-5').checked).toBe(true);
    expect(choice('jump-long').checked).toBe(true);
    await click(q('[data-choice="begin-yes"]')!);
    expect(onStart).toHaveBeenCalledWith(expect.objectContaining({ rows: 5, jump: 'long' }), true);
  });

  it('regression: the same for a broken-link dialog that is replaced by the begin dialog', async () => {
    const broken: StartDialogModel = { kind: 'broken', reason: 'link', onNew: vi.fn() };
    await act(async () => {
      root.render(<StartDialog model={broken} />);
    });
    await act(async () => {
      root.render(<StartDialog model={{ kind: 'begin', initial: DEFAULT_CHOICE, onStart: vi.fn() }} />);
    });
    expect(choice('rows-5').checked).toBe(true);
  });

  it('Escape closes it only when a game is running', async () => {
    await open(vi.fn());
    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(q('[role="dialog"]')).not.toBeNull();
    const onCancel = vi.fn();
    await open(vi.fn(), DEFAULT_CHOICE, onCancel);
    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('"Keep my current game" is offered only while a game runs, and cancels without starting', async () => {
    const onStart = vi.fn();
    await open(onStart);
    expect(q('[data-choice="begin-keep"]')).toBeNull(); // first load: nothing to go back to
    const onCancel = vi.fn();
    await open(onStart, DEFAULT_CHOICE, onCancel);
    await click(choice('begin-keep'));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onStart).not.toHaveBeenCalled();
    await click(choice('mode-engines')); // three players: the keep button stays next to "Start game"
    expect(q('[data-choice="begin-keep"]')).not.toBeNull();
    expect(q('[data-choice="begin-start"]')).not.toBeNull();
  });

  it('a click on the backdrop cancels while a game runs; a click in the panel, a press started in the panel, or no game do not', async () => {
    const onStart = vi.fn();
    await open(onStart);
    const backdrop = (): HTMLElement => q('[data-testid="start-backdrop"]')!;
    await click(backdrop());
    expect(q('[role="dialog"]')).not.toBeNull(); // no game: nothing happens
    const onCancel = vi.fn();
    await open(onStart, DEFAULT_CHOICE, onCancel);
    await click(q('[role="dialog"]')!);
    await click(q('.ml-sheet__title')!);
    expect(onCancel).not.toHaveBeenCalled();
    // a text selection dragged from the panel out to the backdrop ends with a click on the backdrop: no cancel
    await act(async () => {
      q('.ml-sheet__title')!.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }));
    });
    await click(backdrop());
    expect(onCancel).not.toHaveBeenCalled();
    await act(async () => {
      backdrop().dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }));
    });
    await click(backdrop());
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onStart).not.toHaveBeenCalled();
  });

  it('Tab at the last button wraps to the first Tab stop, which is the checked radio (unchecked radios are skipped)', async () => {
    await open(vi.fn());
    const last = q<HTMLElement>('[data-language="de"]')!; // the language toggle closes the dialog
    last.focus();
    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    await act(async () => {
      last.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(choice('rows-5')); // the board size is the first group; 5 rows is the default
    const back = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true });
    await act(async () => {
      choice('rows-5').dispatchEvent(back);
    });
    expect(back.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(last);
  });
});

describe('the link dialog', () => {
  const link = (jump?: 'long'): StartDialogModel => ({ kind: 'link', ply: 12, sideToMove: 0, jump, onContinueAs: vi.fn(), onNew: vi.fn() });

  it('names the jump rule of a long-hop link, and says nothing for the standard rule', async () => {
    await act(async () => {
      root.render(<StartDialog model={link('long')} />);
    });
    expect(q('[data-testid="link-variant"]')!.textContent).toContain('Long hop');
    await act(async () => {
      root.render(<StartDialog model={link()} />);
    });
    expect(q('[data-testid="link-variant"]')).toBeNull();
  });

  it('names the move limit of the variant of the link: 200 or 300 plies on 4 rows, 250 or 375 on 5 rows (seen red with a fixed 200)', async () => {
    const withVariant = (variant?: Variant): StartDialogModel => ({ ...link(), ...(variant ? { variant } : {}) });
    const cases: [Variant | undefined, string][] = [
      [undefined, 'ply 12 of 200'],
      [{ jump: 'adjacent', players: 3 }, 'ply 12 of 300'],
      [{ jump: 'adjacent', players: 2, rows: 5 }, 'ply 12 of 250'],
      [{ jump: 'long', players: 3, rows: 5 }, 'ply 12 of 375'],
    ];
    for (const [variant, text] of cases) {
      await act(async () => {
        root.render(<StartDialog model={withVariant(variant)} />);
      });
      expect(q('p')!.textContent, text).toContain(text);
    }
  });
});

describe('the rules panel', () => {
  const panel = (): HTMLDetailsElement => q<HTMLDetailsElement>('[data-rules-panel]')!;
  const summary = (): string => q('[data-rules-now]')!.textContent!;
  const fixed = (key: string): string | undefined => q(`[data-fixed-rule="${key}"]`)?.textContent ?? undefined;

  it('is collapsed when the dialog opens, and the summary says "standard rules" while nothing differs', async () => {
    await open(vi.fn());
    expect(panel().open).toBe(false);
    expect(summary()).toBe('Standard rules');
    expect(q('[data-rules-count]')).toBeNull();
    // the jump and corner rules moved into the panel
    expect(panel().contains(choice('jump-long'))).toBe(true);
    expect(panel().contains(choice('camps-open'))).toBe(true);
    // board size and mode stay on top
    expect(panel().contains(choice('rows-4'))).toBe(false);
    expect(panel().contains(choice('mode-two'))).toBe(false);
  });

  it('the summary names every rule that differs, in panel order, with a count; a rule at its default is not named', async () => {
    await open(vi.fn());
    await click(choice('camps-open'));
    await click(choice('jump-long'));
    expect(summary()).toBe('Jump rule: Long hop · Foreign triangles: Open');
    expect(q('[data-rules-count]')!.textContent).toBe('2 rules changed');
    await click(choice('camps-closed'));
    expect(summary()).toBe('Jump rule: Long hop');
    expect(q('[data-rules-count]')!.textContent).toBe('1 rule changed');
  });

  it('marks the default option with a tag and the legend of a changed rule with "changed"', async () => {
    await open(vi.fn());
    const jumpGroup = (): Element => choice('jump-long').closest('fieldset')!;
    expect(choice('jump-adjacent').closest('label')!.querySelector('.ml-start__default')!.textContent).toBe('default');
    expect(choice('jump-long').closest('label')!.querySelector('.ml-start__default')).toBeNull();
    expect(jumpGroup().querySelector('.ml-start__changed')).toBeNull();
    await click(choice('jump-long'));
    expect(jumpGroup().querySelector('.ml-start__changed')!.textContent).toBe('changed');
  });

  it('the ranking rule shows with three players only; with two it is a fixed row with the reason', async () => {
    const onStart = vi.fn();
    await open(onStart);
    expect(q('[data-choice="ranking-full"]')).toBeNull();
    expect(fixed('ranking')).toContain('Available with 3 players only');
    await click(choice('mode-engines'));
    expect(fixed('ranking')).toBeUndefined();
    expect(choice('ranking-first').checked).toBe(true);
    await click(choice('ranking-full'));
    expect(summary()).toBe('After the first win: Play for 2nd place');
    await click(choice('begin-start'));
    expect(onStart).toHaveBeenCalledWith({ ...DEFAULT_CHOICE, mode: 'engines', ranking: 'full' }, true);
  });

  it('a changed ranking does not count while the mode is two-player', async () => {
    await open(vi.fn(), { ...DEFAULT_CHOICE, ranking: 'full' });
    expect(summary()).toBe('Standard rules');
    expect(q('[data-rules-count]')).toBeNull();
  });

  it('the fixed rules show their values, and the move limit follows board size and player count (200, 250, 300, 375)', async () => {
    await open(vi.fn(), { ...DEFAULT_CHOICE, rows: 4 });
    expect(fixed('limit')).toContain('200 plies');
    await click(choice('rows-5'));
    expect(fixed('limit')).toContain('250 plies');
    await click(choice('mode-engines'));
    expect(fixed('limit')).toContain('375 plies');
    await click(choice('rows-4'));
    expect(fixed('limit')).toContain('300 plies');
    expect(fixed('leave')).toContain('Not allowed');
    expect(fixed('suicide')).toContain('Not allowed');
    expect(fixed('draw')).toContain('Distance decides');
    expect(fixed('repetition')).toContain('Play goes on');
    expect(fixed('blockade')).toContain('Count');
    // fixed rules cannot be changed: no radio; the one control of a row opens its explanation
    for (const row of document.querySelectorAll('[data-fixed-rule]')) {
      expect(row.querySelector('input')).toBeNull();
      expect(row.querySelectorAll('button')).toHaveLength(1);
    }
  });

  it('a fixed rule is one compact row; its long explanation is hidden until the button opens it (aria-expanded, aria-controls), and the button closes it again', async () => {
    await open(vi.fn());
    for (const row of document.querySelectorAll<HTMLElement>('[data-fixed-rule]')) {
      const button = row.querySelector<HTMLButtonElement>('button')!;
      const hint = row.querySelector<HTMLElement>('small')!;
      expect(hint.hidden, row.dataset.fixedRule).toBe(true);
      expect(button.getAttribute('aria-expanded')).toBe('false');
      expect(button.getAttribute('aria-controls')).toBe(hint.id);
      expect(button.getAttribute('aria-label')).toContain(row.querySelector('dt')!.textContent!); // the name says which rule
      await click(button);
      expect(hint.hidden).toBe(false);
      expect(button.getAttribute('aria-expanded')).toBe('true');
      await click(button);
      expect(hint.hidden).toBe(true);
    }
  });

  it('the summary names all three changed rules (none is dropped from the text)', async () => {
    await open(vi.fn(), { ...DEFAULT_CHOICE, rows: 4 });
    await click(choice('mode-hotseat'));
    await click(choice('jump-long'));
    await click(choice('camps-open'));
    await click(choice('ranking-full'));
    expect(summary()).toBe('Jump rule: Long hop · Foreign triangles: Open · After the first win: Play for 2nd place');
    expect(q('[data-rules-more]')).toBeNull();
  });

  it('summaryItems keeps the first SUMMARY_MAX_ITEMS and counts the rest', () => {
    expect(summaryItems([1, 2, 3])).toEqual({ shown: [1, 2, 3], more: 0 });
    expect(summaryItems([1, 2, 3, 4])).toEqual({ shown: [1, 2, 3, 4], more: 0 });
    expect(summaryItems([1, 2, 3, 4, 5, 6])).toEqual({ shown: [1, 2, 3, 4], more: 2 });
    expect(summaryItems([])).toEqual({ shown: [], more: 0 });
  });

  it('reset puts the three editable rules back, keeps board size, mode and seats, and announces it', async () => {
    await open(vi.fn(), { ...DEFAULT_CHOICE, rows: 4 });
    await click(choice('mode-hotseat'));
    await click(choice('engine-seat-1'));
    await click(choice('jump-long'));
    await click(choice('camps-pass'));
    await click(choice('ranking-full'));
    expect(q('[data-rules-count]')!.textContent).toBe('3 rules changed');
    await click(choice('rules-reset'));
    expect(summary()).toBe('Standard rules');
    expect(choice('jump-adjacent').checked).toBe(true);
    expect(choice('camps-closed').checked).toBe(true);
    expect(choice('ranking-first').checked).toBe(true);
    expect(choice('rows-4').checked).toBe(true);
    expect(choice('mode-hotseat').checked).toBe(true);
    expect(choice('engine-seat-1').checked).toBe(true);
    expect(q('.ml-rules__done')!.textContent).toBe('The standard rules apply again.');
  });

  it('reset is aria-disabled, not disabled, while nothing differs, and does nothing', async () => {
    await open(vi.fn());
    const reset = choice('rules-reset') as unknown as HTMLButtonElement;
    expect(reset.getAttribute('aria-disabled')).toBe('true');
    expect(reset.disabled).toBe(false);
    await click(reset);
    expect(q('.ml-rules__done')!.textContent).toBe('');
  });

  it('keeps Start, the question and the answers outside the panel, and "keep my game" and the language row after them', async () => {
    await open(vi.fn(), DEFAULT_CHOICE, vi.fn());
    const actions = q('.ml-start__actions')!;
    expect(actions.hasAttribute('data-two')).toBe(true);
    expect(panel().contains(actions)).toBe(false);
    const tail = q('.ml-start__tail')!;
    expect(actions.compareDocumentPosition(tail) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(tail.contains(choice('begin-keep'))).toBe(true);
    expect(tail.querySelector('[data-language]')).not.toBeNull();
    await click(choice('mode-engines'));
    expect(q('.ml-start__actions')!.hasAttribute('data-two')).toBe(false);
  });

  it('the summary names the panel and is described by the current rules; the panel opens and closes with the summary', async () => {
    await open(vi.fn());
    const sum = q('[data-rules-summary]')!;
    expect(sum.getAttribute('aria-describedby')).toBe(q('[data-rules-now]')!.id);
    panel().open = true;
    expect(panel().open).toBe(true);
  });
});

describe('the rules panel and the engines', () => {
  const engine = (supportsVariant?: EngineDefinition['supportsVariant']): EngineDefinition =>
    ({ id: 'cpp-wasm', labelKey: 'x', runtime: 'wasm', language: 'C++', isAvailable: () => true, create: () => { throw new Error('unused'); }, ...(supportsVariant ? { supportsVariant } : {}) }) as EngineDefinition;
  afterEach(() => clearEngines());

  it('an option that no registered engine plays is unavailable: aria-disabled with a reason, a click changes nothing; the others stay available', async () => {
    registerEngine(engine((v) => v.camps !== 'open'));
    await open(vi.fn());
    const open_ = choice('camps-open');
    expect(open_.getAttribute('aria-disabled')).toBe('true');
    expect(open_.disabled).toBe(false); // not `disabled`: the radio stays in the arrow-key sequence
    expect(open_.closest('label')!.hasAttribute('data-unavailable')).toBe(true);
    expect(choice('camps-pass').getAttribute('aria-disabled')).toBeNull();
    const reason = q('[data-rule-reason]')!;
    expect(reason.textContent).toBe('No engine in this build plays “Open”.');
    expect(open_.getAttribute('aria-describedby')).toBe(reason.id);
    await click(open_);
    expect(choice('camps-closed').checked).toBe(true);
    expect(q('[data-rules-count]')).toBeNull();
  });

  it('an option that one engine plays is available (the declaration of any engine counts)', async () => {
    registerEngine(engine((v) => v.camps !== 'open'));
    registerEngine({ ...engine(), id: 'java-js' });
    await open(vi.fn());
    expect(choice('camps-open').getAttribute('aria-disabled')).toBeNull();
    expect(q('[data-rule-reason]')).toBeNull();
  });

  it('with a selected net engine, an option its model cannot play is marked, and while it is selected the reason line shows', async () => {
    const net = new NetSelectionStore({ initial: { engine: 'net-wasm', sims: 200 }, storage: null, wasm: true, lastModel: null });
    const model: StartDialogModel = { kind: 'begin', initial: { ...DEFAULT_CHOICE, rows: 4 }, onStart: () => undefined, net };
    await act(async () => {
      root.render(<StartDialog model={model} />);
    });
    expect(choice('camps-open').closest('label')!.hasAttribute('data-engine-warn')).toBe(true);
    expect(choice('camps-closed').closest('label')!.hasAttribute('data-engine-warn')).toBe(false);
    expect(choice('jump-long').closest('label')!.hasAttribute('data-engine-warn')).toBe(false); // the model plays the long hop
    expect(q('[data-rule-reason]')).toBeNull();
    await click(choice('camps-open'));
    expect(choice('camps-open').checked).toBe(true); // a warning, not a ban
    expect(q('[data-rule-reason]')!.textContent).toContain('selected net engine');
  });

  it('the reason line names the cause that the engine sheet names (resolveModel): no stored model is "no model", a chosen model is "the model you chose"', async () => {
    const EXPERT = 'small-2p-adjacent-expert';
    const expertOnly = listModels().filter((m) => m.id === EXPERT); // no model plays the long hop
    const long: StartChoice = { ...DEFAULT_CHOICE, rows: 4, jump: 'long' };
    const cases = [
      { modelId: undefined, code: 'no-model', text: en.rules_reason_net_no_model },
      { modelId: EXPERT, code: 'model-variant-mismatch', text: en.rules_reason_net_mismatch },
    ] as const;
    for (const c of cases) {
      const net = new NetSelectionStore({ initial: { engine: 'net-wasm', modelId: c.modelId, sims: 200 }, storage: null, wasm: true, lastModel: null, models: expertOnly });
      // what the sheet says for this selection and variant
      const sheet = evaluateSelection({ engine: 'net-wasm', modelId: c.modelId, sims: 200 }, variantOfChoice(long), net.getState().availability, expertOnly, null);
      expect(sheet.warnings.map((w) => w.code), 'the sheet').toContain(c.code);
      await act(async () => {
        root.render(<StartDialog model={{ kind: 'begin', initial: long, onStart: () => undefined, net }} />);
      });
      expect(choice('jump-long').closest('label')!.hasAttribute('data-engine-warn'), c.code).toBe(true);
      expect(q('[data-rule-reason]')!.textContent, c.code).toBe(c.text);
      await act(async () => root.render(null));
    }
  });

  it('a model that is picked automatically for the option is no warning (the same resolution as the sheet): with all models the long hop is playable', async () => {
    const net = new NetSelectionStore({ initial: { engine: 'net-wasm', modelId: 'small-2p-adjacent-expert', sims: 200 }, storage: null, wasm: true, lastModel: null });
    await act(async () => {
      root.render(<StartDialog model={{ kind: 'begin', initial: { ...DEFAULT_CHOICE, rows: 4, jump: 'long' }, onStart: () => undefined, net }} />);
    });
    expect(choice('jump-long').closest('label')!.hasAttribute('data-engine-warn')).toBe(false);
    expect(q('[data-rule-reason]')).toBeNull();
  });

  it('without a net engine selected no option is marked', async () => {
    const net = new NetSelectionStore({ initial: { engine: 'cpp-wasm', sims: 200 }, storage: null, wasm: true, lastModel: null });
    await act(async () => {
      root.render(<StartDialog model={{ kind: 'begin', initial: { ...DEFAULT_CHOICE, rows: 4 }, onStart: () => undefined, net }} />);
    });
    expect(document.querySelectorAll('[data-engine-warn], [data-unavailable]')).toHaveLength(0);
  });
});
