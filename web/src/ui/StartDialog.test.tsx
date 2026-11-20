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
    // jump rule, foreign triangles (added 2026-11-20) and game mode; no seat group in the two-player mode
    expect(groups).toHaveLength(3);
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
    expect(onStart).toHaveBeenCalledWith({ jump: 'long', camps: 'closed', mode: 'engines', humanSeat: 2, engineSeat: 2 }, true);
  });

  it('starts from the remembered choice', async () => {
    await open(vi.fn(), { jump: 'long', camps: 'closed', mode: 'engines', humanSeat: 1, engineSeat: 0 });
    expect(choice('jump-long').checked).toBe(true);
    expect(choice('mode-engines').checked).toBe(true);
    expect(choice('human-seat-2').checked).toBe(true);
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
    expect(document.activeElement).toBe(choice('jump-adjacent'));
    const back = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true });
    await act(async () => {
      choice('jump-adjacent').dispatchEvent(back);
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
