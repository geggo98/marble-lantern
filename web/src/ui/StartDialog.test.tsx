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

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = <T extends HTMLElement = HTMLElement>(s: string): T | null => document.querySelector<T>(s);
const choice = (name: string): HTMLInputElement | HTMLButtonElement => q<HTMLInputElement | HTMLButtonElement>(`[data-choice="${name}"]`)!;
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
    expect(groups).toHaveLength(2); // jump rule and game mode; no seat group in the two-player mode
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
    expect(onStart).toHaveBeenCalledWith({ jump: 'long', mode: 'engines', humanSeat: 2, engineSeat: 2 }, true);
  });

  it('starts from the remembered choice', async () => {
    await open(vi.fn(), { jump: 'long', mode: 'engines', humanSeat: 1, engineSeat: 0 });
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

  it('Tab at the last button wraps to the first Tab stop, which is the checked radio (unchecked radios are skipped)', async () => {
    await open(vi.fn());
    const last = choice('begin-no');
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
    expect(document.activeElement).toBe(choice('begin-no'));
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
});
