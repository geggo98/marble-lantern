import { describe, expect, it } from 'vitest';
import { NODE_LEVELS } from '../ui/Panels';
import { formatGameFragment, parseGameFragment } from '../app/game-url';
import { isCustomLevel, isPresetLevel, isValidLevel, levelForNodes, nodesOfLevel } from './level';

describe('level', () => {
  it('tells presets from custom budgets, and nothing in between is valid', () => {
    for (let i = 0; i < NODE_LEVELS.length; i++) expect(isPresetLevel(i)).toBe(true);
    expect(isPresetLevel(NODE_LEVELS.length)).toBe(false);
    expect(isValidLevel(5)).toBe(false);
    expect(isValidLevel(9)).toBe(false);
    expect(isValidLevel(10)).toBe(true);
    expect(isCustomLevel(5_000_000)).toBe(true);
    expect(isValidLevel(5_000_001)).toBe(false);
    expect(isValidLevel(2.5)).toBe(false);
    expect(isValidLevel(-1)).toBe(false);
  });
  it('maps a level to its node budget', () => {
    expect(nodesOfLevel(0)).toBe(NODE_LEVELS[0]);
    expect(nodesOfLevel(4)).toBe(NODE_LEVELS[4]);
    expect(nodesOfLevel(12_345)).toBe(12_345);
    expect(nodesOfLevel(7)).toBe(NODE_LEVELS[2]); // invalid: the fallback level
  });
  it('turns a budget into a preset when it is exactly one, else into a custom level inside the limits', () => {
    expect(levelForNodes(20_000)).toBe(2);
    expect(levelForNodes(7_300.4)).toBe(7300);
    expect(levelForNodes(3)).toBe(10);
    expect(levelForNodes(9e9)).toBe(5_000_000);
  });
});

describe('game link with a custom level', () => {
  const moves = [10 | (20 << 8)];
  it('writes a preset as before and a custom budget as its node count', () => {
    expect(formatGameFragment({ moves, level: 3 })).toMatch(/&l=3$/);
    expect(formatGameFragment({ moves, level: 12_345 })).toMatch(/&l=12345$/);
  });
  it('reads both back, and still rejects a number that is neither', () => {
    expect(parseGameFragment(formatGameFragment({ moves, level: 3 })).level).toBe(3);
    expect(parseGameFragment(formatGameFragment({ moves, level: 12_345 })).level).toBe(12_345);
    expect(() => parseGameFragment(`${formatGameFragment({ moves })}&l=5`)).toThrow();
    expect(() => parseGameFragment(`${formatGameFragment({ moves })}&l=9`)).toThrow();
    expect(() => parseGameFragment(`${formatGameFragment({ moves })}&l=99999999`)).toThrow();
    expect(() => parseGameFragment(`${formatGameFragment({ moves })}&l=0123`)).toThrow();
  });
});
