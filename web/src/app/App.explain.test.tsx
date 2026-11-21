// @vitest-environment jsdom
// The card "Explain this position" in the whole app (fake engine): a link press stores a snapshot equal to the game and navigates to the
// section; the snapshot does not follow later moves; the card is disabled, with the reason, before a game, after it, and for another variant.
// The topic pages are stubs that print what they received as `live`, so these tests do not depend on any figure.
import { act } from 'react';
import type { ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { forgetTopic, topicLoaders } from '../about/AboutTopic';
import { ABOUT_TOPICS } from '../about/routes';
import type { TopicPageProps } from '../about/TopicPage';
import { clearEngines } from '../engines/registry';
import type { EnginesManifest } from '../engines/registry';
import { wonGame2 } from '../engines/testing/won-game';
import { withBoardRows, humanVsEngine, humanVsTwoEngines } from '../game/setup';
import type { GameSetup } from '../game/setup';
import { App } from './App';
import { registerFakeEngine } from './dev-fake';
import { FakeNavigation } from './fake-navigation';
import { encodeMoves, parseHash } from './game-url';

vi.mock('./adapters', () => ({ loadManifest: vi.fn() }));

const file = { path: 'engines/cpp/engine-cpp.wasm', bytes: 1, gzipBytes: 1, sha256: '0'.repeat(64) };
const manifest: EnginesManifest = { v: 1, version: 'test', engines: { 'cpp-wasm': { files: [file], binaryBytes: 1, binaryGzipBytes: 1 } } };
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const TWO_WIN = `#g=v1.${encodeMoves(wonGame2().history)}&h=1&l=2`;

let host: HTMLElement;
let root: Root;
const original = { ...topicLoaders };
const q = <T extends Element = HTMLElement>(selector: string): T | null => document.querySelector<T>(selector);
const all = <T extends Element = HTMLElement>(selector: string): T[] => [...document.querySelectorAll<T>(selector)];

async function until(condition: () => boolean, what: string, ms = 4000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) {
    if (Date.now() > end) throw new Error(`timeout waiting for: ${what}`);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 5));
    });
  }
}
async function click(element: Element): Promise<void> {
  await act(async () => {
    element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
  });
}
const status = (): string => q('.ml-status__who span')?.textContent ?? '';
const plies = (): number => Number(/(\d+)\//.exec(q('.ml-status__ply')?.textContent ?? '')?.[1] ?? -1);

async function humanMove(): Promise<void> {
  for (const piece of all('[data-cell][aria-label*="your piece"]')) {
    await click(piece);
    const target = all('[data-cell][aria-label*="target"]')[0];
    if (target) {
      await click(target);
      return;
    }
  }
  throw new Error('no own piece has a legal move');
}

async function mount(options: { nav?: FakeNavigation; initialSetup?: GameSetup } = {}): Promise<void> {
  window.matchMedia = ((query: string) => ({
    matches: query.includes('reduced') || query.includes('min-width'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined,
  })) as unknown as typeof window.matchMedia;
  clearEngines();
  registerFakeEngine(0);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(
      <App
        manifestLoader={async () => manifest}
        {...(options.nav ? { navigation: options.nav } : {})}
        {...(options.initialSetup ? { initialSetup: options.initialSetup } : {})}
      />,
    );
  });
}

/** Every topic page prints the snapshot it received. */
function stubTopics(): void {
  const Stub = (props: TopicPageProps): ReactNode => (
    <main>
      <h1 tabIndex={-1}>stub {props.topic}/{props.section ?? ''}</h1>
      <pre data-testid="live">{JSON.stringify(props.live?.snapshot ?? null)}</pre>
    </main>
  );
  for (const t of ABOUT_TOPICS) topicLoaders[t] = async () => ({ default: Stub as never });
}

const goTo = async (hash: string): Promise<void> => {
  await act(async () => {
    location.hash = hash;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  });
};
const live = (): { id: number; history: number[]; ply: number; over: boolean; variant: unknown } | null => {
  const text = q('[data-testid="live"]')?.textContent;
  return text === undefined ? null : (JSON.parse(text) as ReturnType<typeof live>);
};
const link = (section: string): HTMLAnchorElement | null => q<HTMLAnchorElement>(`a[data-explain-link="${section}"]`);

beforeEach(() => {
  localStorage.setItem('ml.start-choice', '{"v":2,"rows":4}');
  stubTopics();
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  localStorage.clear();
  Object.assign(topicLoaders, original);
  for (const t of ABOUT_TOPICS) forgetTopic(t);
  history.replaceState(null, '', location.pathname);
  vi.restoreAllMocks();
});

describe('the card "Explain this position"', () => {
  it('a link press stores the game as it is and navigates to the section; a later move does not change the snapshot', async () => {
    await mount();
    await click(q('[data-choice="begin-no"]')!);
    await until(() => status() === 'Your move' && plies() === 1, 'the engine opened');
    await humanMove();
    await until(() => plies() === 3 && status() === 'Your move', 'the engine answered');
    const played = parseHash(location.hash); // the address shows the game: an independent source for the history
    expect(played.kind).toBe('game');
    const moves = played.kind === 'game' ? played.link.moves : [];
    expect(moves).toHaveLength(3);

    expect(all('a[data-explain-link]').map((a) => a.getAttribute('href'))).toEqual([
      '#/about/classical/budget', '#/about/classical/alpha-beta', '#/about/classical/evaluation', '#/about/inference/mcts', '#/about/inference/forward-pass',
    ]);
    await click(link('budget')!);
    await until(() => live() !== null, 'the topic page of the link');
    expect(location.hash).toBe('#/about/classical/budget');
    expect(q('h1')?.textContent).toBe('stub classical/budget');
    const first = live()!;
    expect(first.history).toEqual(moves);
    expect(first.ply).toBe(3);
    expect(first.over).toBe(false);
    expect(first.variant).toEqual({ jump: 'adjacent', players: 2 });

    await goTo(''); // back to the game
    await until(() => q('.ml-status__ply') !== null, 'the play screen');
    await humanMove();
    await until(() => plies() === 5 && status() === 'Your move', 'two more plies');
    await goTo('#/about/inference/mcts');
    await until(() => live() !== null, 'the topic page again');
    expect(live()).toEqual(first); // the snapshot did not follow the game

    await goTo('');
    await until(() => link('evaluation') !== null, 'the card');
    await click(link('evaluation')!); // a new press takes a new snapshot
    await until(() => live()?.ply === 5, 'the new snapshot');
    expect(live()!.id).toBeGreaterThan(first.id);
  });

  it('before a game runs the card is disabled with its reason and has no links; a page without a press has no snapshot', async () => {
    await mount();
    expect(q('[data-explain-card]')?.hasAttribute('data-disabled')).toBe(true);
    expect(q('[data-explain-reason]')?.textContent).toBe('Start a game first.');
    expect(all('a[data-explain-link]')).toHaveLength(0);
    expect(all('[data-explain-link]')).toHaveLength(5);
    await goTo('#/about/classical/budget');
    await until(() => live() !== null || q('h1') !== null, 'the topic page');
    expect(live()).toBeNull();
  });

  it('three players: disabled with the variant reason', async () => {
    await mount({ initialSetup: humanVsTwoEngines(1, 2) });
    await until(() => plies() >= 1, 'the game runs');
    expect(q('[data-explain-card]')?.hasAttribute('data-disabled')).toBe(true);
    expect(q('[data-explain-reason]')?.textContent).toBe('Only for two players, adjacent jumps, on the 4-row board.');
    expect(all('a[data-explain-link]')).toHaveLength(0);
  });

  it('five rows: disabled with the variant reason', async () => {
    await mount({ initialSetup: withBoardRows(humanVsEngine(1, 2), 5) });
    await until(() => plies() >= 1, 'the game runs');
    expect(q('[data-explain-card]')?.hasAttribute('data-disabled')).toBe(true);
    expect(q('[data-explain-reason]')?.textContent).toBe('Only for two players, adjacent jumps, on the 4-row board.');
  });

  it('a finished game: disabled with the over reason', async () => {
    await mount({ nav: new FakeNavigation(TWO_WIN) });
    await until(() => q('[data-choice="link-side-to-move"]') !== null, 'the link dialog');
    await click(q('[data-choice="link-side-to-move"]')!);
    await until(() => q('.ml-result') !== null, 'the result card');
    expect(q('[data-explain-card]')?.hasAttribute('data-disabled')).toBe(true);
    expect(q('[data-explain-reason]')?.textContent).toBe('The game is over; there is no move to explain.');
    expect(all('a[data-explain-link]')).toHaveLength(0);
  });
});
