// @vitest-environment jsdom
// The lazy topic pages: loading, failing and retrying, scroll and focus, the "On this page" jump, previous and next, the glossary popover
// and the hub cards. Expected texts come from the stub catalogs of the topics, which name their own keys.
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n } from '../i18n/i18n';
import { AboutScreen } from '../ui/Screens';
import { AboutTopic, forgetTopic, topicLoaders } from './AboutTopic';
import { ABOUT_TOPICS } from './routes';
import type { AboutTopic as Topic } from './routes';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLElement;
let root: Root;
const original = { ...topicLoaders };
const scrollIntoView = vi.fn();
const scrollTo = vi.fn();

beforeEach(() => {
  i18n.setLanguage('en');
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  scrollIntoView.mockClear();
  scrollTo.mockClear();
  Element.prototype.scrollIntoView = scrollIntoView;
  window.scrollTo = scrollTo as unknown as typeof window.scrollTo;
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  Object.assign(topicLoaders, original);
  for (const t of ABOUT_TOPICS) forgetTopic(t);
  vi.restoreAllMocks();
  i18n.setLanguage('en');
});

const q = <T extends HTMLElement = HTMLElement>(s: string): T | null => host.querySelector<T>(s);
/** Lets the lazy import and the render settle: waits until no loading line is left (a dynamic import takes a while the first time). */
const flush = async (): Promise<void> => {
  for (let i = 0; i < 500; i++) {
    await act(async () => {
      await new Promise((r) => setTimeout(r, 10));
    });
    // Settled: the page has its h1 or the failure alert. (Not "no status element": a figure's narration line is a status element too.)
    if (host.querySelector('h1, [role="alert"]') !== null) return;
  }
  throw new Error('the page stayed in its loading state');
};
const click = async (el: Element): Promise<void> => {
  await act(async () => {
    el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
};
const show = async (topic: Topic, section?: string, onJump: (s: string) => void = () => undefined): Promise<void> => {
  await act(async () => root.render(<AboutTopic topic={topic} section={section} onJumpSection={onJump} />));
  await flush();
};

describe('AboutTopic: loading', () => {
  it('shows a loading line while the chunk loads, then the page', async () => {
    let release: () => void = () => undefined;
    topicLoaders.classical = () => new Promise((resolve) => (release = () => resolve(original.classical())));
    await act(async () => root.render(<AboutTopic topic="classical" onJumpSection={() => undefined} />));
    expect(q('.ml-hint[role="status"]')?.textContent).toBe('Loading the page');
    expect(q('h1')).toBeNull();
    await act(async () => release());
    await flush();
    expect(q('.ml-hint[role="status"]')).toBeNull();
    expect(q('h1')?.textContent).toBe('The classical algorithm');
  });

  it.each(ABOUT_TOPICS)('loads the topic %s with exactly one h1 and a breadcrumb back to the hub', async (topic) => {
    await show(topic);
    expect(host.querySelectorAll('h1')).toHaveLength(1);
    expect(q<HTMLAnchorElement>('a.ml-about__crumb')?.getAttribute('href')).toBe('#/about');
  });

  it('shows an alert with a retry button when the chunk cannot be loaded, and the page after the retry', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const loader = vi.fn(() => original.tuning());
    loader.mockRejectedValueOnce(new Error('Failed to fetch dynamically imported module'));
    topicLoaders.tuning = loader;
    await show('tuning');
    expect(q('[role="alert"]')?.textContent).toContain('The page could not be loaded');
    expect(q('h1')).toBeNull();
    await click(q('[role="alert"] button')!);
    await flush();
    expect(q('[role="alert"]')).toBeNull();
    expect(q('h1')?.textContent).toBe('How the hyperparameters were chosen');
    expect(loader).toHaveBeenCalledTimes(2);
  });

  it('keeps the alert when the retry fails again', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    topicLoaders.tuning = () => Promise.reject(new Error('offline'));
    await show('tuning');
    await click(q('[role="alert"] button')!);
    await flush();
    expect(q('[role="alert"]')).not.toBeNull();
  });
});

describe('AboutTopic: scroll and focus', () => {
  it('without a section: scrolls to the top and focuses the h1', async () => {
    await show('classical');
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    expect(document.activeElement).toBe(q('h1'));
    expect(q('h1')?.getAttribute('tabindex')).toBe('-1');
  });

  it('with a section: scrolls it into view and focuses its heading', async () => {
    await show('classical', 'alpha-beta');
    const heading = q('#about-classical-alpha-beta');
    expect(heading?.tagName).toBe('H2');
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView.mock.contexts[0]).toBe(heading);
    expect(document.activeElement).toBe(heading);
  });

  it('with an unknown section: behaves as without one', async () => {
    await show('classical', 'no-such-section');
    expect(scrollIntoView).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(q('h1'));
  });

  it('moves to the new section when only the section changes', async () => {
    await show('classical', 'board');
    await show('classical', 'minimax');
    expect(document.activeElement).toBe(q('#about-classical-minimax'));
  });

  it('focuses the term of the glossary route', async () => {
    await show('glossary', 'node-budget');
    expect(document.activeElement).toBe(q('#about-glossary-node-budget'));
  });
});

describe('AboutTopic: the page', () => {
  it('lists the sections in "On this page" and hands a plain click on an entry to onJumpSection', async () => {
    const jump = vi.fn();
    await show('classical', undefined, jump);
    const link = q<HTMLAnchorElement>('nav.ml-about__toc a[href="#/about/classical/alpha-beta"]')!;
    expect(link.textContent).toBe('Alpha-beta pruning');
    const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
    await act(async () => {
      link.dispatchEvent(event);
    });
    expect(jump).toHaveBeenCalledWith('alpha-beta');
    expect(event.defaultPrevented).toBe(true);
  });

  it('leaves a click with a modifier key to the browser (a new tab)', async () => {
    const jump = vi.fn();
    await show('classical', undefined, jump);
    const link = q<HTMLAnchorElement>('nav.ml-about__toc a[href="#/about/classical/alpha-beta"]')!;
    const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ctrlKey: true });
    await act(async () => {
      link.dispatchEvent(event);
    });
    expect(jump).not.toHaveBeenCalled();
    expect(event.defaultPrevented).toBe(false);
  });

  it('leaves a middle click to the browser as well', async () => {
    const jump = vi.fn();
    await show('classical', undefined, jump);
    const link = q<HTMLAnchorElement>('nav.ml-about__toc a[href="#/about/classical/alpha-beta"]')!;
    const event = new MouseEvent('click', { bubbles: true, cancelable: true, button: 1 });
    await act(async () => {
      link.dispatchEvent(event);
    });
    expect(jump).not.toHaveBeenCalled();
  });

  it('renders a heading and a paragraph per section, and the reading list with its links', async () => {
    await show('classical');
    expect(host.querySelectorAll('section.ml-about__section h2')).toHaveLength(11 + 1); // eleven sections and the reading list
    expect(q('#about-classical-board')?.textContent).toBe('Board and moves');
    const reading = [...host.querySelectorAll<HTMLAnchorElement>('.ml-about__reading a')].map((a) => a.getAttribute('href'));
    expect(reading).toEqual([
      'https://www.chessprogramming.org/Minimax',
      'https://www.chessprogramming.org/Alpha-Beta',
      'https://www.chessprogramming.org/Futility_Pruning',
      'https://www.chessprogramming.org/Texel%27s_Tuning_Method',
      'https://sites.ualberta.ca/~nathanst/papers/sturtevant2019chinesecheckers.pdf',
      'https://en.wikipedia.org/wiki/Elo_rating_system',
    ]);
  });

  it('links to the previous and the next topic, and has none beyond the ends', async () => {
    await show('classical');
    expect(q('a[rel="prev"]')?.getAttribute('href')).toBe('#/about/architecture');
    expect(q('a[rel="next"]')?.getAttribute('href')).toBe('#/about/inference');
    await show('architecture');
    expect(q('a[rel="prev"]')).toBeNull();
    expect(q('a[rel="next"]')?.getAttribute('href')).toBe('#/about/classical');
    await show('glossary');
    expect(q('a[rel="prev"]')?.getAttribute('href')).toBe('#/about/tuning');
    expect(q('a[rel="next"]')).toBeNull();
  });

  it('switches every text at once when the language changes, with no loading state', async () => {
    await show('classical');
    expect(q('h1')?.textContent).toBe('The classical algorithm');
    await act(async () => i18n.setLanguage('de'));
    expect(q('.ml-hint[role="status"]')).toBeNull();
    expect(q('h1')?.textContent).toBe('Der klassische Algorithmus');
    expect(q('a[rel="next"]')?.textContent).toContain('Inferenz und das Netz');
  });

  it('renders the glossary page alphabetically, in the active language', async () => {
    await show('glossary');
    const names = (): string[] => [...host.querySelectorAll('.ml-about__term dt')].map((e) => e.firstChild?.textContent ?? '');
    expect(names()).toEqual([...names()].sort((a, b) => a.localeCompare(b, 'en', { sensitivity: 'base' })));
    expect(names()).toContain('Tablebase');
    await act(async () => i18n.setLanguage('de'));
    expect(names()).toContain('Endspieltabelle');
    expect(host.querySelector('#about-glossary-branching-factor')?.textContent).toContain('(branching factor)');
  });
});

describe('TermPopover', () => {
  const termButton = (): HTMLButtonElement => q<HTMLButtonElement>('.ml-about__section .ml-term__btn[data-term="game-tree"]')!;

  it('opens on a click with the definition and a link into the glossary, and closes on a second click', async () => {
    await show('classical');
    expect(termButton().getAttribute('aria-expanded')).toBe('false');
    expect(q('.ml-term__pop')).toBeNull();
    await click(termButton());
    expect(termButton().getAttribute('aria-expanded')).toBe('true');
    const pop = q('.ml-term__pop')!;
    expect(termButton().getAttribute('aria-controls')).toBe(pop.id);
    expect(pop.textContent).toContain('Positions as nodes and moves as edges, grown from the current position as the root.');
    expect(pop.querySelector('a')?.getAttribute('href')).toBe('#/about/glossary/game-tree');
    await click(termButton());
    expect(q('.ml-term__pop')).toBeNull();
  });

  it('closes on Escape and returns the focus to the term', async () => {
    await show('classical');
    await click(termButton());
    q('.ml-term__pop a')!.focus();
    await act(async () => {
      q('.ml-term__pop a')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(q('.ml-term__pop')).toBeNull();
    expect(document.activeElement).toBe(termButton());
  });

  it('closes on a press outside, and stays open on a press inside', async () => {
    await show('classical');
    await click(termButton());
    await act(async () => {
      q('.ml-term__pop')!.dispatchEvent(new Event('pointerdown', { bubbles: true }));
    });
    expect(q('.ml-term__pop')).not.toBeNull();
    await act(async () => {
      document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }));
    });
    expect(q('.ml-term__pop')).toBeNull();
  });

  it('shows the German definition, with the English term, after a language switch', async () => {
    await show('classical');
    await click(termButton());
    await act(async () => i18n.setLanguage('de'));
    const pop = q('.ml-term__pop')!;
    expect(pop.textContent).toContain('Stellungen als Knoten und Züge als Kanten, gewachsen aus der aktuellen Stellung als Wurzel.');
    expect(pop.textContent).toContain('(game tree)');
  });
});

describe('AboutScreen: the hub', () => {
  it('keeps today\'s content in its order and adds one card per topic below it', async () => {
    await act(async () => root.render(<AboutScreen />));
    const order = [...host.querySelectorAll('main > *')].map((e) => e.tagName + (e.className ? `.${String(e.className).split(' ')[0]}` : ''));
    expect(order).toEqual(['DIV.ml-hub__head', 'DIV.ml-prose', 'SECTION', 'DIV.ml-links', 'SECTION.ml-learn']);
    const cards = [...host.querySelectorAll<HTMLAnchorElement>('.ml-learn__card a.ml-learn__link')];
    expect(cards.map((a) => a.getAttribute('href'))).toEqual(ABOUT_TOPICS.map((t) => `#/about/${t}`));
    expect(cards[1]!.textContent).toBe('The classical algorithm');
    expect(host.querySelector('.ml-learn__card[data-topic="classical"] .ml-kicker')?.textContent).toBe('Topic 2');
  });

  it('has the card texts in German too', async () => {
    i18n.setLanguage('de');
    await act(async () => root.render(<AboutScreen />));
    expect(host.querySelector('.ml-learn__card[data-topic="classical"] a')?.textContent).toBe('Der klassische Algorithmus');
    expect(host.querySelector('.ml-learn__card[data-topic="classical"] .ml-kicker')?.textContent).toBe('Thema 2');
  });

  it('shows no notice by default and the notice for a route that was not found', async () => {
    await act(async () => root.render(<AboutScreen />));
    expect(q('[role="status"]')).toBeNull();
    await act(async () => root.render(<AboutScreen notFound />));
    expect(q('[role="status"]')?.textContent).toBe('This page does not exist. Here is the overview instead.');
    expect(q('h1')?.textContent).toBe('About');
  });

  it('renders a topic page instead of the hub when the route names a topic', async () => {
    await act(async () => root.render(<AboutScreen topic="inference" />));
    await flush();
    expect(q('h1')?.textContent).toBe('Inference and the network');
    expect(q('.ml-learn')).toBeNull();
  });
});
