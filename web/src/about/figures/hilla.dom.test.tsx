// @vitest-environment jsdom
// Figure J6 (Hilla data flow) in jsdom: the nine states through the hooks of the spec (data-step, data-state, data-wait, data-here, data-arrow,
// data-edge, data-wire-*), the keys of the stepper, the narration with its ghost texts, the accessible names, both languages, reduced motion, and
// that every text of the figure comes from the catalog. Heights at 320 px and 1280 px are measured in e2e/about-hilla.spec.ts.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../../i18n/i18n';
import { HillaFigure } from './HillaFigure';
import { javaWebCatalog } from './java-web-catalog';
import type { Catalog } from './common';
import { BOXES, STEPS } from './hilla-model';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const catalog = javaWebCatalog as unknown as Catalog;
let host: HTMLElement;
let root: Root;
beforeEach(() => {
  i18n.setLanguage('en');
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
  i18n.setLanguage('en');
});

const render = async (): Promise<void> => {
  await act(async () => root.render(<HillaFigure catalog={catalog} number={[7, 1]} />));
};
const q = <T extends Element = HTMLElement>(selector: string): T => {
  const el = host.querySelector<T>(selector);
  if (!el) throw new Error(`nothing matches ${selector}`);
  return el;
};
const all = <T extends Element = HTMLElement>(selector: string): T[] => Array.from(host.querySelectorAll<T>(selector));
const text = (el: Element | null | undefined): string => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
const fig = (): HTMLElement => q('#fig-hilla');
const click = async (label: string): Promise<void> => {
  const b = all<HTMLButtonElement>('.ml-fig-stepper button').find((x) => x.getAttribute('aria-label') === label);
  if (!b) throw new Error(`no button ${label}`);
  await act(async () => b.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })));
};
const key = async (k: string): Promise<void> => {
  await act(async () => {
    q('.ml-fig-stepper').dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
  });
};
const goto = async (n: number): Promise<void> => {
  await key('Home'); // step 1
  for (let i = 1; i < n; i++) await key('ArrowRight');
};
const active = (selector: string): string[] => all(`${selector}[data-state="active"]`).map((e) => e.getAttribute('data-box') ?? e.getAttribute('data-arrow') ?? e.getAttribute('data-edge') ?? '');

describe('J6 idle state', () => {
  it('shows the six boxes, three per lane, in ink, with the stepper at 0 / 8', async () => {
    await render();
    expect(fig().getAttribute('data-step')).toBe('0');
    expect(all('[data-lane]').map((l) => l.getAttribute('data-lane'))).toEqual(['browser', 'server']);
    expect(all('[data-lane="browser"] [data-box]').map((b) => b.getAttribute('data-box'))).toEqual(['call', 'generated', 'client']);
    expect(all('[data-lane="server"] [data-box]').map((b) => b.getAttribute('data-box'))).toEqual(['engines', 'endpoint', 'controller']);
    expect(all('[data-box]').every((b) => !b.hasAttribute('data-state'))).toBe(true);
    expect(all('[data-box]').map((b) => b.getAttribute('data-kind'))).toEqual(['own', 'gen', 'lib', 'own', 'own', 'lib']);
    expect(text(q('.ml-fig-stepper__pos'))).toContain('0 / 8');
    expect(q('[data-wait]').getAttribute('data-wait')).toBe('none');
    expect(all('[data-here="true"]')).toHaveLength(0);
    expect(q('.ml-fig-wire').getAttribute('data-wire-step')).toBe('idle');
    expect(text(q('[data-live="body"]'))).toBe(text(all('.ml-fig-wire__hint')[0]));
  });

  it('is a figure named by its title, with a group for the lanes and a section for each lane', async () => {
    await render();
    const title = text(q('.ml-fig__title'));
    expect(title.length).toBeGreaterThan(0);
    expect(fig().getAttribute('aria-labelledby')).toBe('fig-hilla-title');
    expect(q('.ml-fig-lanes').getAttribute('role')).toBe('group');
    expect(q('.ml-fig-lanes').getAttribute('aria-label')).toBe(title);
    for (const lane of all('[data-lane]')) {
      expect(lane.tagName).toBe('SECTION');
      expect(text(host.querySelector(`#${lane.getAttribute('aria-labelledby')}`)).length).toBeGreaterThan(0);
      expect(lane.querySelector('ol')).not.toBeNull();
    }
    expect(q('.ml-fig-lanes__band').getAttribute('aria-hidden')).toBe('true');
    expect(text(q('.ml-fig-wire h4'))).not.toBe('');
  });

  it('lists the eight steps for screen readers, visually hidden, with a name', async () => {
    await render();
    const list = q('ol.ml-visually-hidden');
    expect(list.getAttribute('aria-label')).toBeTruthy();
    expect(Array.from(list.querySelectorAll('li'))).toHaveLength(8);
    for (const li of Array.from(list.querySelectorAll('li'))) expect(text(li).length).toBeGreaterThan(0);
  });

  it('adds no tab stop besides the stepper buttons', async () => {
    await render();
    const stops = all('button, a, input, select, textarea, [tabindex]').filter((e) => !e.closest('.ml-fig__foot'));
    expect(stops.every((e) => e.closest('.ml-fig-stepper'))).toBe(true);
  });
});

describe('J6 steps', () => {
  it('lights the boxes, arrows and edges of the table in every step', async () => {
    await render();
    for (let n = 1; n <= 8; n++) {
      await goto(n);
      const s = STEPS[n]!;
      expect(fig().getAttribute('data-step'), s.id).toBe(String(n));
      expect(active('[data-box]').sort(), s.id).toEqual([...s.boxes].sort());
      expect(all('[data-box][aria-current="step"]').map((b) => b.getAttribute('data-box')).sort(), s.id).toEqual([...s.boxes].sort());
      expect(all('[data-box]:not([data-state="active"])').every((b) => b.getAttribute('data-state') === 'dim'), s.id).toBe(true);
      expect(active('[data-arrow]').sort(), s.id).toEqual([...s.arrows].sort());
      expect(all('[data-edge][data-state="active"]').map((e) => e.getAttribute('data-edge')).sort(), s.id).toEqual([...s.edges].sort());
      expect(q('.ml-fig-wire').getAttribute('data-wire-step'), s.id).toBe(s.id);
      expect(q('[data-wait]').getAttribute('data-wait'), s.id).toBe(s.wait);
      expect(all('[data-here="true"]').map((l) => l.getAttribute('data-lane')), s.id).toEqual(s.here === 'browser' || s.here === 'server' ? [s.here] : []);
      if (s.where) {
        expect(q('[data-live="tags"] [data-wire-where]').getAttribute('data-wire-where'), s.id).toBe(s.where);
        expect(q('[data-live="tags"] [data-wire-form]').getAttribute('data-wire-form'), s.id).toBe(s.form);
      }
      expect(text(q('.ml-fig__status [role="status"]')).length, s.id).toBeGreaterThan(10);
    }
  });

  it('shows the card body of the step, with the changed part marked', async () => {
    await render();
    await goto(4);
    expect(text(q('[data-live="body"]'))).toContain('POST /connect/GameEndpoint/choose');
    expect(text(q('[data-live="body"] .k'))).toBe('POST');
    expect(text(q('[data-live="body"] .h'))).toBe('‹token›');
    await goto(7);
    expect(all('[data-live="body"] .h').map(text)).toContain('‹double›');
    await goto(6);
    expect(text(q('[data-live="body"]'))).toContain('slots.tryAcquire(30, SECONDS)');
  });

  it('keeps the live line the only live region and the ghosts hidden from screen readers', async () => {
    await render();
    expect(all('[aria-live]')).toHaveLength(1);
    const ghosts = all('.ml-fig__status-line--ghost');
    expect(ghosts).toHaveLength(9);
    expect(ghosts.every((g) => g.getAttribute('aria-hidden') === 'true')).toBe(true);
    expect(all('.ml-fig-wire__ghost').every((g) => g.getAttribute('aria-hidden') === 'true')).toBe(true);
    expect(all('.ml-fig-wire__ghost pre').length + all('pre.ml-fig-wire__ghost').length).toBe(8);
    // one ghost per distinct pair of tags
    expect(all('.ml-fig-wire__tags > .ml-fig-wire__ghost')).toHaveLength(5);
  });

  it('puts the step label in front of the narration from step 1 on, and none at idle', async () => {
    await render();
    expect(q('.ml-fig__status [role="status"]').querySelector('.ml-fig__step')).toBeNull();
    await goto(3);
    expect(text(q('.ml-fig__status [role="status"] .ml-fig__step'))).toMatch(/3.*8/);
    expect(text(q('.ml-fig__status [role="status"]')).length).toBeGreaterThan(10);
  });
});

describe('J6 stepper keys', () => {
  it('goes one step with Right and Left, to step 1 with Home and to step 8 with End, and stops at the bounds', async () => {
    await render();
    await key('ArrowRight');
    expect(fig().getAttribute('data-step')).toBe('1');
    await key('ArrowRight');
    expect(fig().getAttribute('data-step')).toBe('2');
    await key('ArrowLeft');
    expect(fig().getAttribute('data-step')).toBe('1');
    await key('End');
    expect(fig().getAttribute('data-step')).toBe('8');
    await key('ArrowRight');
    expect(fig().getAttribute('data-step')).toBe('8');
    await key('Home');
    expect(fig().getAttribute('data-step')).toBe('1');
    await key('ArrowLeft');
    await key('ArrowLeft');
    expect(fig().getAttribute('data-step')).toBe('0');
  });

  it('resets with the reset button and steps with the forward button', async () => {
    await render();
    const labels = all('.ml-fig-stepper button').map((b) => b.getAttribute('aria-label')!);
    expect(labels).toHaveLength(4);
    await click(labels[3]!);
    await click(labels[3]!);
    expect(fig().getAttribute('data-step')).toBe('2');
    await click(labels[0]!);
    expect(fig().getAttribute('data-step')).toBe('0');
  });

  it('keeps the live region polite while stopped and silent while the playback runs', async () => {
    vi.useFakeTimers();
    try {
      await render();
      expect(q('[role="status"]').getAttribute('aria-live')).toBe('polite');
      const play = all<HTMLButtonElement>('.ml-fig-stepper button')[2]!;
      await act(async () => play.dispatchEvent(new MouseEvent('click', { bubbles: true })));
      expect(q('[role="status"]').getAttribute('aria-live')).toBe('off');
      await act(async () => {
        vi.advanceTimersByTime(1200 * 9);
      });
      expect(fig().getAttribute('data-step')).toBe('8');
      expect(q('[role="status"]').getAttribute('aria-live')).toBe('polite');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('J6 motion', () => {
  it('renders the travelling dots while motion is allowed', async () => {
    await render();
    expect(all('.ml-fig-arr__dot').length).toBeGreaterThan(0);
  });

  it('renders neither the play button nor a dot under reduced motion', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('reduce'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined }));
    await render();
    expect(all('.ml-fig-stepper button')).toHaveLength(3);
    expect(all('.ml-fig-arr__dot')).toHaveLength(0);
  });
});

describe('J6 texts', () => {
  it('takes every label from the catalog: no key name shows in either language', async () => {
    for (const lang of ['en', 'de'] as const) {
      i18n.setLanguage(lang);
      await render();
      await goto(8);
      const shown = text(fig());
      expect(shown, lang).not.toMatch(/fig\.hilla|kit\.[a-z]+\./);
      for (const id of Object.keys(BOXES)) expect(text(q(`[data-box="${id}"] .ml-fig-lanes__role`)).length, `${lang} ${id}`).toBeGreaterThan(0);
      expect(text(q('.ml-fig__caption')).length, lang).toBeGreaterThan(40);
      expect(all('.ml-fig-legend__item'), lang).toHaveLength(7);
    }
  });

  it('keeps the roles within 18 characters and the tags within their maximum in both languages', async () => {
    for (const lang of ['en', 'de'] as const) {
      i18n.setLanguage(lang);
      await render();
      for (const r of all('.ml-fig-lanes__role')) expect(text(r).length, `${lang} ${text(r)}`).toBeLessThanOrEqual(18);
      for (const r of all('.ml-fig-wire__tags .ml-fig-wire__ghost [class*="tag"]')) expect(text(r).length, `${lang} ${text(r)}`).toBeLessThanOrEqual(16);
      expect(text(q('.ml-fig-lanes__wait')).length).toBeLessThanOrEqual(10);
    }
  });
});
