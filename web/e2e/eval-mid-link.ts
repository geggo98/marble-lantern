// The 30 plies of src/about/data/eval-mid.json as a game-URL payload (encodeMoves of its history), for the browser tests of the live Info figures.
// The browser tests cannot import the generators (see fixtures.ts); src/about/live/eval.test.ts checks that this value is still what encodeMoves gives.
export const EVAL_MID_MOVES = 'BkOeYgdzWQCnxNEEZsICz6uCL3hnOxu9pTRzzmdzpQo9bMSNbnOwg-IyCknC8McXpQ5tKaA';
/** Side 0 is to move after 30 plies; the person plays side 0, so the page asks only how to continue. */
export const EVAL_MID_HASH = `#g=v1.${EVAL_MID_MOVES}&h=0&l=0`;
