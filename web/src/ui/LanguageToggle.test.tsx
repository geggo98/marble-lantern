// @vitest-environment jsdom
// The language switch: one component for the header and the dialogs. A click changes the language of the whole UI in place;
// inside a dialog it keeps the dialog open, keeps the choices made in it and keeps the focus inside.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_CHOICE } from '../app/start-choice';
import { catalogs, i18n } from '../i18n/i18n';
import { LanguageToggle } from './LanguageToggle';
import { StartDialog } from './StartDialog';
import type { StartDialogModel } from './StartDialog';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const q = <T extends HTMLElement = HTMLElement>(s: string): T | null => document.querySelector<T>(s);
const click = async (el: Element): Promise<void> => {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
  });
};
const render = async (node: React.JSX.Element): Promise<void> => {
  await act(async () => root.render(node));
};

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  act(() => i18n.setLanguage('en'));
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  act(() => i18n.setLanguage('en'));
});

describe('LanguageToggle', () => {
  it('is a labelled group of two buttons; the pressed one is the current language, each button names its own language', async () => {
    await render(<LanguageToggle />);
    const group = q('[role="group"]')!;
    expect(group.getAttribute('aria-label')).toBe(catalogs.en.language);
    const buttons = Array.from(group.querySelectorAll('button'));
    expect(buttons.map((b) => [b.textContent, b.getAttribute('lang'), b.getAttribute('aria-pressed')])).toEqual([
      ['EN', 'en', 'true'],
      ['DE', 'de', 'false'],
    ]);
  });

  it('a click switches the language, updates the pressed state and the group label', async () => {
    await render(<LanguageToggle />);
    await click(q('[data-language="de"]')!);
    expect(i18n.current).toBe('de');
    expect(q('[data-language="de"]')!.getAttribute('aria-pressed')).toBe('true');
    expect(q('[data-language="en"]')!.getAttribute('aria-pressed')).toBe('false');
    expect(q('[role="group"]')!.getAttribute('aria-label')).toBe(catalogs.de.language);
  });

  it('the header copy and the dialog copy are the same component with the same state', async () => {
    await render(
      <>
        <LanguageToggle />
        <LanguageToggle inDialog />
      </>,
    );
    await click(q('[data-language-toggle="dialog"] [data-language="de"]')!);
    expect(q('[data-language-toggle="header"] [data-language="de"]')!.getAttribute('aria-pressed')).toBe('true');
  });
});

describe('the toggle inside the start dialog', () => {
  const open = async (): Promise<void> => {
    const model: StartDialogModel = { kind: 'begin', initial: DEFAULT_CHOICE, onStart: vi.fn() };
    await render(<StartDialog model={model} />);
  };

  it('sits after the actions (last in the tab order) and does not take the first focus from the primary button', async () => {
    await open();
    expect(document.activeElement).toBe(q('[data-choice="begin-yes"]'));
    const dialog = q('[role="dialog"]')!;
    const tabbables = Array.from(dialog.querySelectorAll<HTMLElement>('button, input'));
    expect(tabbables[tabbables.length - 1]).toBe(q('[data-language="de"]'));
  });

  it('switching to German re-renders the text in place: the dialog stays, the choices stay, the focus stays on the toggle', async () => {
    await open();
    await click(q('[data-choice="mode-hotseat"]')!);
    await click(q('[data-choice="jump-long"]')!);
    q<HTMLElement>('[data-language="de"]')!.focus();
    const dialog = q('[role="dialog"]')!;
    const before = dialog.textContent;
    await click(q('[data-language="de"]')!);
    expect(q('[role="dialog"]')).toBe(dialog); // the same element: no remount
    expect(dialog.querySelector('h2')!.textContent).toBe(catalogs.de.start_title);
    expect(dialog.textContent).not.toBe(before);
    expect((q('[data-choice="mode-hotseat"]') as HTMLInputElement).checked).toBe(true);
    expect((q('[data-choice="jump-long"]') as HTMLInputElement).checked).toBe(true);
    expect(document.activeElement).toBe(q('[data-language="de"]'));
    expect(dialog.contains(document.activeElement)).toBe(true);
  });

  it('the link dialog and the broken-link dialog carry it as well', async () => {
    await render(<StartDialog model={{ kind: 'link', ply: 4, sideToMove: 0, onContinueAs: vi.fn(), onNew: vi.fn() }} />);
    expect(q('[data-dialog="link"] [data-language="de"]')).not.toBeNull();
    await render(<StartDialog model={{ kind: 'broken', reason: 'link', onNew: vi.fn() }} />);
    expect(q('[data-dialog="broken"] [data-language="de"]')).not.toBeNull();
  });
});
