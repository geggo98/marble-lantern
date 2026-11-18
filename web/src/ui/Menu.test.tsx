// @vitest-environment jsdom
// The HUD menu: a button that opens a modal sheet; focus, Escape, close button, backdrop, swipe, inert background.
import { act, useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Menu, MenuButton } from './Menu';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function Harness(): React.JSX.Element {
  const [open, setOpen] = useState(false);
  return (
    <>
      <div id="page">
        <MenuButton expanded={open} controlsId="menu-test" onClick={() => setOpen(true)} />
        <button type="button" id="other">Other</button>
      </div>
      {open && (
        <Menu id="menu-test" onClose={() => setOpen(false)}>
          <button type="button" id="first">First</button>
          <button type="button" id="last">Last</button>
        </Menu>
      )}
    </>
  );
}

let host: HTMLElement;
let root: Root;
const q = <T extends Element = HTMLElement>(s: string): T | null => document.querySelector<T>(s);
const trigger = (): HTMLElement => q('[aria-controls="menu-test"]')!;
const dialog = (): HTMLElement | null => q('[role="dialog"]');

async function press(target: Element, key: string, init: KeyboardEventInit = {}): Promise<void> {
  await act(async () => {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }));
  });
}
async function open(): Promise<void> {
  await act(async () => {
    trigger().focus();
    trigger().click();
  });
}
async function pointer(target: Element, type: string, clientY: number, timeStamp?: number): Promise<void> {
  await act(async () => {
    const event = new MouseEvent(type, { bubbles: true, cancelable: true, clientX: 10, clientY, button: 0 });
    Object.defineProperty(event, 'pointerId', { value: 1 });
    Object.defineProperty(event, 'pointerType', { value: 'touch' });
    if (timeStamp !== undefined) Object.defineProperty(event, 'timeStamp', { value: timeStamp });
    target.dispatchEvent(event);
  });
}

beforeEach(async () => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => root.render(<Harness />));
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

describe('MenuButton', () => {
  it('announces the popup and its state', async () => {
    expect(trigger().getAttribute('aria-expanded')).toBe('false');
    expect(trigger().getAttribute('aria-haspopup')).toBe('dialog');
    await open();
    expect(trigger().getAttribute('aria-expanded')).toBe('true');
  });
});

describe('Menu', () => {
  it('opens as a modal dialog that the button controls, and moves focus inside', async () => {
    expect(dialog()).toBeNull();
    await open();
    const d = dialog()!;
    expect(d.getAttribute('aria-modal')).toBe('true');
    expect(d.id).toBe('menu-test');
    expect(document.getElementById(d.getAttribute('aria-labelledby')!)).not.toBeNull();
    expect(d.contains(document.activeElement)).toBe(true);
  });

  it('makes the page inert while open and releases it on close', async () => {
    await open();
    expect(q('#page')!.parentElement!.hasAttribute('inert')).toBe(true); // the host div, a child of body
    expect(dialog()!.closest('body > *')!.hasAttribute('inert')).toBe(false);
    await press(dialog()!, 'Escape');
    expect(host.hasAttribute('inert')).toBe(false);
  });

  it('closes on Escape and returns focus to the button', async () => {
    await open();
    await press(document.activeElement!, 'Escape');
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(trigger());
  });

  it('closes on Escape when focus is not inside (Safari does not focus a tapped checkbox or button)', async () => {
    await open();
    (document.activeElement as HTMLElement).blur();
    await press(document.body, 'Escape');
    expect(dialog()).toBeNull();
  });

  it('Escape does not close the menu while another dialog stands above it', async () => {
    await open();
    const above = document.createElement('section');
    above.setAttribute('role', 'dialog');
    document.body.appendChild(above);
    await press(document.body, 'Escape');
    expect(dialog()).not.toBeNull();
    above.remove();
  });

  it('returns focus only after the page is no longer inert (a browser refuses focus on an inert element)', async () => {
    const original = HTMLElement.prototype.focus;
    HTMLElement.prototype.focus = function (this: HTMLElement, options?: FocusOptions): void {
      if (this.closest('[inert]')) return; // what browsers do
      original.call(this, options);
    };
    try {
      await open();
      await press(document.activeElement!, 'Escape');
      expect(document.activeElement).toBe(trigger());
    } finally {
      HTMLElement.prototype.focus = original;
    }
  });

  it('closes with the visible close button and returns focus', async () => {
    await open();
    const close = dialog()!.querySelector<HTMLElement>('[data-menu-close]')!;
    expect(close.getAttribute('aria-label')).toBeTruthy();
    await act(async () => close.click());
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(trigger());
  });

  it('closes on a tap on the backdrop, not on a tap inside the sheet', async () => {
    await open();
    await act(async () => q('#first')!.click());
    expect(dialog()).not.toBeNull();
    const backdrop = dialog()!.parentElement!;
    await pointer(backdrop, 'pointerdown', 5);
    await act(async () => backdrop.click());
    expect(dialog()).toBeNull();
  });

  it('keeps Tab inside: last wraps to first, Shift+Tab from first wraps to last', async () => {
    await open();
    const items = Array.from(dialog()!.querySelectorAll<HTMLElement>('button'));
    const first = items[0]!;
    const last = items[items.length - 1]!;
    expect(last.id).toBe('last');
    last.focus();
    await press(last, 'Tab');
    expect(document.activeElement).toBe(first);
    await press(first, 'Tab', { shiftKey: true });
    expect(document.activeElement).toBe(last);
  });

  it('closes on a swipe down on the grip, and not on a short slow one', async () => {
    await open();
    const grab = dialog()!.querySelector<HTMLElement>('[data-menu-grab]')!;
    await pointer(grab, 'pointerdown', 100, 1000);
    await pointer(grab, 'pointermove', 130, 1500);
    await pointer(grab, 'pointerup', 130, 2000); // 30 px in 1 s: too slow and too short
    expect(dialog()).not.toBeNull();
    await pointer(grab, 'pointerdown', 100, 1000);
    await pointer(grab, 'pointermove', 220, 1300);
    await pointer(grab, 'pointerup', 220, 1400); // 120 px
    expect(dialog()).toBeNull();
  });
});
