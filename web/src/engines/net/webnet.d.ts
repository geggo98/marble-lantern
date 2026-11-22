// The exports of engine-net.js (JS target) and of `TeaVM.wasmGC.load(...).exports` (Wasm GC target): the facade
// sternhalma.webnet.WebNet of teavm-net/. It is the net-MCTS engine without the forward pass.
//
// Control calls return a string and never throw: "ok <fields>" (fields separated by one space) or
// "error <code> <message>". Every String argument must be passed (the JS target fails on a missing one).
// Bulk data crosses as Float32Array (no copy on the JS side of the facade; the Wasm GC build copies).
//
// The calls of one move, in order:
//   create(...); setWeightsInfo(...); [setTable(...)]          once per game, or when the variant or options change
//   n = begin(history)                                          "ok n"; history is "m1,m2,..." (empty at the start)
//   while (n > 0) { x = inputs(); {from,to,values} = net(x); n = apply(from, to, values) }
//   best()                                                      "ok move rootValue sims nodes exactLeaves raceMove moves visits values"
//
// Error codes: illegal-history, bad-options, not-ready, no-weights-info, bad-weights-info, bad-outputs, non-finite,
// table-magic, no-table, internal.

export interface WebNetModule {
  /**
   * `jump` "adjacent" | "long"; `players` "2" | "3"; `sims` simulations per move; `batch` leaves per forward pass
   * (1 to 64); `seed` a 32-bit integer for noise and sampling; `options` comma separated key=value, "" for the
   * defaults: cpuct (1), ptemp (1), noise (0), alpha (0.3), temp (0), fpu (0), win1 (1), reuse (0), s1 (0),
   * race (0, 1 or 2), ms (time budget per move, none). s1 and race need setTable first. Returns "ok". Drops the tree.
   */
  /** `camps`: the foreign-corner rule, "closed", "pass-through" or "open"; always passed (also by legalMoves and outcome). `rows`: the board size, "4" or "5" (empty is 4), also always passed. A search on "5" needs a network that lists 5 rows (setWeightsInfoRows); setWeightsInfo, a 4-row network, then fails with `unsupported`. */
  create(jump: string, players: string, sims: number, batch: number, seed: number, options: string, camps: string, rows: string): string;
  /**
   * The plane set id of the weights metadata ("plain", "classic1", "classic1-planes", "classic1v") and the width of
   * the value head (2, or 3 for a three-player net and for classic1v). Returns "ok <inputSize> <valueWidth>", with
   * inputSize = 122 * planes.
   */
  setWeightsInfo(planes: string, valueWidth: number): string;
  /**
   * setWeightsInfo with the value of the metadata key triangle_rows of the weights ("4", "5", "4,5"; "" for a file
   * without the key, which means 4). Fails with "error unsupported" when the game of the last create is on a board
   * size the network does not list. Not called by the page yet (5-row network plan, web integration).
   */
  setWeightsInfoRows?(planes: string, valueWidth: number, rows: string): string;
  /** The inflated rows2.bin, as an Int8Array over the same memory. Only for the options s1 and race. Returns "ok <states>". */
  setTable(file: Int8Array): string;
  /** "ok <moves>", comma separated; empty when the game is over. Stateless. */
  legalMoves(history: string, jump: string, players: string, camps: string, rows: string): string;
  /** "ok ongoing", "ok win <side>" or "ok draw". Stateless. */
  outcome(history: string, jump: string, players: string, camps: string, rows: string): string;
  /** Starts the search of the position after `history`. "ok <n>": positions to evaluate; 0 when best() is ready (one legal move, race move). */
  begin(history: string): string;
  /** "ok <n>": positions still waiting; the same number until apply. */
  prepare(): string;
  /**
   * The encoded positions of the pending batch: n * inputSize floats, canonical orientation, channels last, as the Java
   * Encoder writes them (the input of NetBackend.evaluate). An empty array when nothing is pending.
   */
  inputs(): Float32Array;
  /**
   * The outputs of the network for the pending batch, as NetBackend.evaluate returns them (the classical priors are
   * already in them, as in ForwardPass.forward): policyFrom and policyTo with 121 floats per position (the "from" and
   * "to" logit of each canonical cell), values with valueWidth floats per position (relative players, mover first).
   * Java does the softmax over the legal moves and the mapping to absolute players. Returns "ok <n>": the positions
   * to evaluate next, 0 when the search is finished.
   */
  apply(policyFrom: Float32Array, policyTo: Float32Array, values: Float32Array): string;
  /**
   * "ok <move> <rootValue> <sims> <nodes> <exactLeaves> <raceMove 0|1> <moves> <visits>": the move to play (an engine
   * move code, as in the history), the mean value of the root for its mover, simulations run, nodes in the tree,
   * simulations that ended in an exact leaf, whether the race module chose the move, and two comma separated lists:
   * the legal moves of the root and their visit counts (both empty when no search ran). A ninth field `<values>` follows: the
   * mean value of each of those moves for the side that plays it, comma separated in the order of the moves, as
   * `Double.toString` writes it (0 for a move without visits). An engine-net file older than this field answers with eight fields;
   * NetCore then leaves `root.values` undefined.
   */
  best(): string;
}
