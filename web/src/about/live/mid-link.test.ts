// The game-link payload of the example position (the 30 plies of eval-mid.json) that e2e/about-live.spec.ts opens. The browser tests cannot
// import the generators (Playwright runs in Node, which cannot load board/cells.generated.json), so the spec holds the text and this test
// checks that it is still the encoding of the data file.
import { describe, expect, it } from 'vitest';
import evalMid from '../data/eval-mid.json';
import { decodeMoves, encodeMoves } from '../../app/game-url';

/** Keep equal to EXAMPLE_MID in e2e/about-live.spec.ts. */
export const EXAMPLE_MID_PAYLOAD = 'BkOeYgdzWQCnxNEEZsICz6uCL3hnOxu9pTRzzmdzpQo9bMSNbnOwg-IyCknC8McXpQ5tKaA';

const HISTORY = (evalMid.history as { from: number; to: number }[]).map((m) => m.from | (m.to << 8));

describe('the example position as a game link', () => {
  it('is the encoding of the 30 plies of eval-mid.json', () => {
    expect(encodeMoves(HISTORY)).toBe(EXAMPLE_MID_PAYLOAD);
  });
  it('decodes back to the same 30 moves', () => {
    expect(decodeMoves(EXAMPLE_MID_PAYLOAD)).toEqual(HISTORY);
  });
});
