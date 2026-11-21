// @vitest-environment jsdom
// Terms we coined ourselves (data/glossary-internal.json, mocked here to ['elo', 'tablebase']): the tag "internal" in the glossary and in
// the popover, the hint text, the filter option, and the display rule of the English term in the German glossary.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';

vi.mock('../data/glossary-internal.json', () => ({ default: ['elo', 'tablebase'] }));

import { allTerms, isInternal } from '../i18n/glossary';
import { TermPopover } from '../kit/TermPopover';
import GlossaryPage from './glossary';
import { filterTerms } from './glossary-filter';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
beforeEach(() => {
  i18n.setLanguage('en');
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  Element.prototype.scrollIntoView = vi.fn();
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo;
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  i18n.setLanguage('en');
});

const show = async (): Promise<void> => {
  await act(async () => root.render(<GlossaryPage topic="glossary" section={undefined} onJumpSection={() => undefined} />));
};

describe('internal terms', () => {
  it('reads the ids of the data file', () => {
    expect(isInternal('elo')).toBe(true);
    expect(isInternal('batch')).toBe(false);
    expect(allTerms('en').filter((e) => e.internal).map((e) => e.id).sort()).toEqual(['elo', 'tablebase']);
  });

  it('tags exactly the internal terms in the glossary, with the hint, in both languages', async () => {
    await show();
    expect([...host.querySelectorAll('.ml-about__term')].filter((t) => t.querySelector('[data-internal]')).length).toBe(2);
    expect(host.querySelector('[data-internal]')?.textContent).toBe('internal');
    expect(host.querySelector('.ml-glossary__hint')?.textContent).toContain('coined for this project');
    await act(async () => i18n.setLanguage('de'));
    expect(host.querySelector('[data-internal]')?.textContent).toBe('intern');
    expect(host.querySelector('.ml-glossary__hint')?.textContent).toContain('geprägt');
  });

  it('has a filter option "internal" that shows only the internal terms', async () => {
    await show();
    const button = host.querySelector<HTMLButtonElement>('button[data-kind="internal"]')!;
    await act(async () => button.click());
    expect(button.getAttribute('aria-pressed')).toBe('true');
    expect(host.querySelectorAll('.ml-about__term')).toHaveLength(2);
  });

  it('filterTerms keeps the internal entries and combines with the text', () => {
    const terms = allTerms('en');
    expect(filterTerms(terms, 'internal', '').map((e) => e.id).sort()).toEqual(['elo', 'tablebase']);
    expect(filterTerms(terms, 'internal', 'elo').map((e) => e.id)).toContain('elo');
    expect(filterTerms(terms, 'internal', 'zobrist')).toEqual([]);
  });

  it('shows the tag and the hint in the popover of an internal term only', async () => {
    await act(async () => root.render(<><TermPopover id="elo" /><TermPopover id="batch" /></>));
    const buttons = host.querySelectorAll<HTMLButtonElement>('.ml-term__btn');
    await act(async () => buttons[0]!.click());
    expect(host.querySelector('.ml-term__pop [data-internal]')?.textContent).toBe('internal');
    expect(host.querySelector('.ml-term__pop .ml-term__hint')).not.toBeNull();
    await act(async () => buttons[1]!.click());
    expect(host.querySelectorAll('.ml-term__pop')).toHaveLength(2);
    expect(host.querySelectorAll('.ml-term__pop [data-internal]')).toHaveLength(1);
  });
});

describe('the English term in the German glossary', () => {
  it('shows no repeated English term next to the owner examples, in the page and in the popover', async () => {
    i18n.setLanguage('de');
    await show();
    const head = (id: string): string => host.querySelector(`dt[id$="${id}"]`)?.textContent ?? 'missing';
    expect(head('f32-f16')).toBe('F32 und F16');
    expect(head('convolution')).toBe('Faltung (convolution)');
    expect(head('flop')).toBe('FLOP (floating-point operation)');
    await act(async () => root.render(<TermPopover id="flop" />));
    await act(async () => host.querySelector<HTMLButtonElement>('.ml-term__btn')!.click());
    expect(host.querySelector('.ml-term__pop .ml-term__en')?.textContent).toContain('floating-point operation'); // once, not 'FLOP (FLOP (...))'
    expect((host.querySelector('.ml-term__pop')?.textContent ?? '').match(/FLOP/g)?.length).toBeLessThanOrEqual(2);
  });

  it('never shows an English term in the English glossary', async () => {
    await show();
    expect(host.querySelector('.ml-term__en')).toBeNull();
  });
});
