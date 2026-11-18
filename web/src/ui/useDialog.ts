// The behaviour every modal dialog of the app shares: focus moves in, Tab stays inside, Escape asks to close, focus
// returns to where it was. Used by the start dialog and the menu (see Menu.tsx). With `isolate` the rest of the page is
// made inert while the dialog is open, so that a screen reader and a keyboard cannot leave it.
import { useEffect, useRef } from 'react';

export const TABBABLE = 'button:not([disabled]), [href], input:not([disabled]), summary, [tabindex]:not([tabindex="-1"])';

/** True for an element inside a closed `<details>` that is not its summary: it cannot take focus. */
function hiddenInClosedDetails(el: HTMLElement): boolean {
  const closed = el.closest('details:not([open])');
  if (!closed) return false;
  return !(el.tagName === 'SUMMARY' && el.parentElement === closed);
}

export interface UseDialogOptions {
  /** Called on Escape. Without it Escape does nothing (a dialog that must be answered). */
  onEscape?: (() => void) | undefined;
  /** Focus moves to the first element again whenever this value changes (the start dialog changes its content). */
  focusKey?: unknown;
  /** Where focus goes on close. Safari does not focus a button that is tapped, so `document.activeElement` is no safe memory. */
  returnFocusTo?: React.RefObject<HTMLElement | null> | undefined;
  /** Make every other child of `document.body` inert while the dialog is mounted. */
  isolate?: boolean;
}

export function useDialog<T extends HTMLElement>({ onEscape, focusKey, isolate = false, returnFocusTo }: UseDialogOptions = {}): {
  ref: React.RefObject<T | null>;
  onKeyDown: (event: React.KeyboardEvent) => void;
} {
  const ref = useRef<T | null>(null);
  const opener = useRef<Element | null>(typeof document === 'undefined' ? null : document.activeElement);

  useEffect(() => {
    const root = ref.current;
    const first = root?.querySelector<HTMLElement>('[data-autofocus]') ?? root?.querySelector<HTMLElement>(TABBABLE);
    first?.focus();
  }, [focusKey]);

  // Declared before the focus return: cleanups run in this order, and focus cannot go to an element that is still inert.
  useEffect(() => {
    if (!isolate || !ref.current) return undefined;
    const own = ref.current.closest('body > *');
    const changed: Element[] = [];
    for (const child of Array.from(document.body.children)) {
      if (child === own || child.hasAttribute('inert')) continue;
      child.setAttribute('inert', '');
      changed.push(child);
    }
    return () => changed.forEach((child) => child.removeAttribute('inert'));
  }, [isolate]);

  useEffect(() => {
    const back = returnFocusTo?.current ?? opener.current;
    return () => {
      if (back instanceof HTMLElement && back.isConnected) back.focus();
    };
  }, []);

  // Escape also counts when focus is outside the dialog: Safari does not focus a button or checkbox that is tapped, so after
  // a tap in the dialog the key events go to the body. Only the topmost dialog answers.
  useEffect(() => {
    if (!onEscape) return undefined;
    const listener = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      const dialogs = document.querySelectorAll('[role="dialog"]');
      if (dialogs[dialogs.length - 1] !== ref.current) return;
      onEscape();
    };
    document.addEventListener('keydown', listener);
    return () => document.removeEventListener('keydown', listener);
  });

  const onKeyDown = (event: React.KeyboardEvent): void => {
    if (event.key === 'Escape' && onEscape) {
      event.stopPropagation();
      onEscape();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = Array.from(ref.current?.querySelectorAll<HTMLElement>(TABBABLE) ?? []).filter(
      (el) => el.tabIndex >= 0 && !hiddenInClosedDetails(el),
    );
    const firstEl = items[0];
    const lastEl = items[items.length - 1];
    if (!firstEl || !lastEl) return;
    const active = document.activeElement;
    if (!ref.current?.contains(active)) {
      event.preventDefault();
      firstEl.focus();
    } else if (event.shiftKey && active === firstEl) {
      event.preventDefault();
      lastEl.focus();
    } else if (!event.shiftKey && active === lastEl) {
      event.preventDefault();
      firstEl.focus();
    }
  };

  return { ref, onKeyDown };
}
