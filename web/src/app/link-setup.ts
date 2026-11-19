// Between the game URL and the game setup: pure functions, no DOM, no engine.
import type { GameSetup, Seat } from '../game/setup';
import { controllerSeats, humanVsEngine, variantOf } from '../game/setup';
import type { Side } from '../game/board-state';
import { DEFAULT_VARIANT, isDefault } from '../game/variant';
import type { UrlSnapshot } from './game-nav';
import type { GameLink } from './game-url';
import { variantOfLink } from './game-url';

/** The game a link describes: its variant and seats; `humanSide` and `level` fill what a two-player link leaves out. */
export function setupFromLink(link: GameLink, fallback: { humanSide: Side; level: number }): GameSetup {
  const level = link.level ?? fallback.level;
  const variant = variantOfLink(link);
  if (variant.players === 3) {
    const seats = (link.seats ?? []).map((kind): Seat => (kind === 'human' ? { kind } : { kind, level }));
    return { variant, seats };
  }
  return humanVsEngine(link.humanSide ?? fallback.humanSide, level, variant.jump);
}

/** What the URL has to say about a setup and a game: variant and seats appear for the variants only. */
export function snapshotOf(moves: readonly number[], setup: GameSetup, humanSide: Side, level: number): UrlSnapshot {
  const variant = variantOf(setup);
  if (isDefault(variant)) return { moves, humanSide, level };
  return { moves, humanSide, level, variant, ...(variant.players === 3 ? { seats: controllerSeats(setup) } : {}) };
}

/** What the address shows for a link: the link itself, with `humanSide` and `level` filled in. */
export function snapshotOfLink(link: GameLink, fallback: { humanSide: Side; level: number }): UrlSnapshot {
  return {
    moves: link.moves,
    humanSide: link.humanSide ?? fallback.humanSide,
    level: link.level ?? fallback.level,
    ...(link.variant ? { variant: link.variant } : {}),
    ...(link.seats ? { seats: link.seats } : {}),
  };
}

/** True for a link that is not the standard game: the app continues it without asking which side the person plays. */
export const isVariantLink = (link: GameLink): boolean => !isDefault(link.variant ?? DEFAULT_VARIANT);
