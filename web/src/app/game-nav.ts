// The link between the game and the browser history (docs/web-ui.md, "Game URL"): one history entry per full turn.
//
//   pushState    after the human's move (the entry is the position after that move), after undo, after a new game, after "Play on"
//   replaceState when the engine's answer arrives, and when only the side or the strength changes
//
// The navigation port keeps the code free of `window`, so that tests run it against a fake history.
import { playState } from '../game/board-state';
import type { Side } from '../game/board-state';
import type { ControllerSeat } from '../game/game';
import { DEFAULT_VARIANT, rankingOf, sameVariant } from '../game/variant';
import type { Variant } from '../game/variant';
import { formatGameFragment } from './game-url';

export interface NavEvent {
  hash: string;
  /** The `state` of the history entry that became current. */
  state: unknown;
}

export interface NavigationPort {
  readonly hash: string;
  readonly state: unknown;
  /** The full address, for "copy link". */
  readonly href: string;
  push(fragment: string): void;
  replace(fragment: string): void;
  /** Fires when the user moves through the history or changes the fragment. Not for our own push and replace. */
  subscribe(listener: (event: NavEvent) => void): () => void;
}

/** Marks the entries this app wrote, so that a pasted link can be told from Back and Forward. */
const OWN = { ml: 'game' } as const;

export const isOwnEntry = (state: unknown): boolean =>
  typeof state === 'object' && state !== null && (state as { ml?: unknown }).ml === OWN.ml;

export function browserNavigation(): NavigationPort {
  return {
    get hash() {
      return globalThis.location?.hash ?? '';
    },
    get state() {
      return globalThis.history?.state;
    },
    get href() {
      return globalThis.location?.href ?? '';
    },
    push(fragment) {
      globalThis.history.pushState(OWN, '', fragment);
    },
    replace(fragment) {
      globalThis.history.replaceState(OWN, '', fragment);
    },
    subscribe(listener) {
      const onPop = (event: PopStateEvent): void => listener({ hash: location.hash, state: event.state });
      window.addEventListener('popstate', onPop);
      return () => window.removeEventListener('popstate', onPop);
    },
  };
}

/** What the URL says about the game. */
export interface UrlSnapshot {
  moves: readonly number[];
  /** Two players: the side of the human. Ignored for three players. */
  humanSide: Side;
  level: number;
  /** Missing means the default variant. */
  variant?: Variant;
  /** Three players: who plays each seat. */
  seats?: readonly ControllerSeat[];
}

const sameMoves = (a: readonly number[], b: readonly number[]): boolean => a.length === b.length && a.every((m, i) => m === b[i]);

const sameSeats = (a: readonly ControllerSeat[] | undefined, b: readonly ControllerSeat[] | undefined): boolean =>
  a === b || (a !== undefined && b !== undefined && a.length === b.length && a.every((s, i) => s === b[i]));

/** The seat of the player who made the last move is a human seat. */
function lastMoverIsHuman(snapshot: UrlSnapshot): boolean {
  const variant = snapshot.variant ?? DEFAULT_VARIANT;
  const mover = playState(snapshot.moves, variant).lastMover; // a seat that is out is skipped under full ranking
  return mover === null ? false : variant.players === 2 ? mover === snapshot.humanSide : snapshot.seats?.[mover] === 'human';
}

export class GameUrlSync {
  private written: UrlSnapshot | null = null;
  private readonly nav: NavigationPort;

  constructor(nav: NavigationPort) {
    this.nav = nav;
  }

  /** The URL already shows this snapshot (the page was opened with it, or the user went to it): write nothing. */
  adopt(snapshot: UrlSnapshot): void {
    this.written = snapshot;
  }

  /** Writes the snapshot to the URL if it differs from what the URL shows. */
  observe(snapshot: UrlSnapshot): 'push' | 'replace' | null {
    const before = this.written;
    if (before && sameMoves(before.moves, snapshot.moves) && before.humanSide === snapshot.humanSide && before.level === snapshot.level &&
      sameVariant(before.variant ?? DEFAULT_VARIANT, snapshot.variant ?? DEFAULT_VARIANT) && sameSeats(before.seats, snapshot.seats)) {
      return null;
    }
    this.written = snapshot;
    const fragment = formatGameFragment(snapshot);
    const extended =
      before !== null &&
      snapshot.moves.length > before.moves.length &&
      before.moves.every((m, i) => m === snapshot.moves[i]);
    let how: 'push' | 'replace';
    const playedOn = rankingOf(snapshot.variant ?? DEFAULT_VARIANT) === 'full' && rankingOf(before?.variant ?? DEFAULT_VARIANT) !== 'full';
    if (before === null) how = 'replace'; // first state
    else if (sameMoves(before.moves, snapshot.moves)) how = playedOn ? 'push' : 'replace'; // "Play on" is a step of its own; else side or strength only
    else if (extended) how = lastMoverIsHuman(snapshot) ? 'push' : 'replace'; // human move: new entry; engine answer: same entry
    else how = 'push'; // undo or new game
    if (how === 'push') this.nav.push(fragment);
    else this.nav.replace(fragment);
    return how;
  }

  /** The address of the game as the URL shows it now. */
  get href(): string {
    return this.nav.href;
  }
}
