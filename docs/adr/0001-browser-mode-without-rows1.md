# ADR 0001: The browser mode ships the small race table only

- **Status:** accepted
- **Date:** 2026-11-18
- **Decided by:** the project owner, after the measurements in [offline mode](../offline-mode.md)
- **Code:** merge commit `d8f68c2` on `main` (branch `exp/offline`); the main commits are listed under [References](#references)

**TL;DR:** The offline browser build uses the classical player `BROWSER_SPEC` with the small race table `rows2.bin` (2.45 MB as gzip, 13.1 MB in memory). The large table `rows1.bin` (254 MB) does not ship for now. At an equal search budget this costs about 56 Elo against the strongest classical player `BEST_SPEC`; the network player loses nothing. A later "strength pack" (part of `rows1.bin`, about 17 MB gzip) would restore full strength in the measurements, but is not built. This ADR lists every variant that was measured and records one open thesis (l) about exact reproduction of the table by a learned model.

## Terms

- **Race table:** for every configuration of one player's ten stones inside a region of the board, the exact number of moves to fill the goal corner when no other stones are on the board. One byte per configuration, numbered by a combinatorial rank, so the file is a dense array without keys.
- **Row:** the two-player board has rows -8 to 8 in a player's own orientation; the goal corner is rows 5 to 8. Row 1 is the first row past the centre line and has 8 cells.
- **`rows2.bin`:** the race table for rows 2 to 8 (28 cells, 13,123,110 configurations, 13,123,286 bytes).
- **`rows1.bin`:** the race table for rows 1 to 8 (36 cells, 254,186,856 configurations, 254,187,064 bytes). Older documents of the project say "242 MB" for it; that is the same file in MiB. MB in this ADR is 10^6 bytes.
- **k:** the number of the side's own stones in row 1 at a `rows1.bin` lookup. For k = 0 the configuration lies inside the `rows2.bin` region.
- **Leaf evaluator:** the evaluation at the leaves of the alpha-beta search. It adds race arithmetic over a race table to a linear evaluation.
- **Race play:** in a position without contact, the player picks the move whose result has the smallest table count, without searching.
- **Race decision:** for two configurations a and b, whether `count(a) ≤ count(b)`. An estimator "agrees" when it gives the same answer as `rows1.bin`.
- **Push estimator:** an estimate of a `rows1.bin` count without reading it: push every row-1 stone to the nearest free cell of row 2, count one move per stone, add the `rows2.bin` count of the result, then add a correction (one value per k, or one per 8-bit occupancy pattern of row 1).
- **Variant names** (`maxk:N`, `estimate`, `estimate2`) are the arena's names for a changed race table: `maxk:N` returns the exact `rows1.bin` entry when k ≤ N and otherwise a fallback (the push estimator, or with `maxk:N:rows2` the `rows2.bin` behaviour, where the evaluator keeps its base value); `estimate` and `estimate2` answer every lookup with a push estimator.
- **Elo figures:** results of the project's node-limited arena: both players search 20,000 nodes per move (about 12.5 CPU-ms on the measurement machine, an Apple M3 Max), 300 opening pairs per seed, seeds 33 and 34, Elo of the variant minus `BEST_SPEC`. "Pooled" is the mean of the two seeds. The 95 % confidence intervals are in [offline mode, arena results](../offline-mode.md#arena-results).
- **Layout L:** `rows1.bin` rewritten grouped by k, then by the row-1 pattern, each pattern with a sub-table over the 28 cells of rows 2 to 8.
- **Mirror fold:** the board is symmetric under left-right reflection; a folded table keeps only row-1 patterns p with p ≤ mirror(p) (palindromic patterns whole), so folded sizes are an upper bound.

## Context

The project plans a browser UI with an offline mode: the classical engine or the network runs entirely in the page (WebAssembly or WebGPU), without a server.

`BEST_SPEC` reads `rows1.bin` in two places: its leaf evaluator `racelin1-r3` and its race play (`racerows=1`). Round 2 of the experiments found that the race arithmetic at the leaves is worth about +45 Elo with `rows1.bin` and nothing with `rows2.bin`, because the `rows2.bin` region covers almost no leaf of a search that still has contact ([endgame and opening](../endgame-and-opening.md#the-race-module)).

`rows1.bin` is too large for a page: 254 MB in memory per tab and 24 to 51 MB compressed on the wire. On mobile browsers that is likely to fail, and on desktop it is a large first download for an optional engine. The network player (MCTS) does not read `rows1.bin`: its default is `racerows=2`, and all network results of the project were measured with it.

The browser constrains the compression format. Measured on 2026-09-09 in Chrome 152, `CompressionStream` and `DecompressionStream` accept only `gzip`, `deflate` and `deflate-raw`; `brotli` and `zstd` throw a `TypeError`. zstd or xz in the page therefore need a WebAssembly decoder.

The open question was how much strength each way of leaving out or shrinking `rows1.bin` costs. The measurements of 2026-11-17 and 2026-11-18 answer it; method and raw results are in [offline mode](../offline-mode.md).

## Decision

> In the context of the offline browser build of the classical player,
> facing a 254 MB race table that does not fit a browser page and a browser that decodes only gzip and deflate natively,
>
> we decided for `BROWSER_SPEC` (`BEST_SPEC` with the evaluator `racelin-r3` and `racerows=2`, so both roles read `rows2.bin` only), shipped as `rows2.bin.gz` of 2.45 MB with a manifest of length and SHA-256,
> and against shipping `rows1.bin` whole, loading a subset of its blocks, replacing it with an estimator, or building a partial "strength pack" now,
>
> to achieve a small first download (2.45 MB), 13.1 MB of table memory and decoding with the browser's built-in stream,
> accepting about -56 Elo against `BEST_SPEC` at an equal node budget, all of it in the leaf evaluator.

`BROWSER_SPEC` has the same weights as `BEST_SPEC`: `racelin-r3` and `racelin1-r3` both wrap the linear evaluation `linear-r3` and differ only in the table. The measured loss is therefore the table alone.

Two guards keep the decision in force. `RaceTable.rows1Requests()` in Java and `RaceEvaluator::rows1_requests()` in the C++ port count every request for `rows1.bin`; the tests `BrowserSpecTest` (Java) and `browser_spec_parses_and_uses_the_rows2_table_only` (C++) assert that a `BROWSER_SPEC` game leaves the counter unchanged.

### When to revisit

The strength pack (option 4 below) becomes worth building when all three of these are measured and acceptable:

1. **Lookup cost** of the pack in a WebAssembly build, from memory or from OPFS (the browser's origin-private file system) through a synchronous access handle in a worker, in a time-limited match. Node-limited Elo cannot see this cost.
2. **Memory on mobile browsers** for about 90 MB of raw table, in particular Safari on iOS.
3. **Decoder size:** the 9.8 MB zstd figure needs a WebAssembly zstd decoder in the page; without it the pack is about 17 MB as gzip.

Also revisit when the target browsers support `zstd` in `DecompressionStream`, or when thesis l below is confirmed.

## Consequences

- **Strength:** about -56 Elo pooled against `BEST_SPEC` at 20,000 nodes per move (-62.6 and -49.0 for the two seeds). The evaluator on `rows2.bin` alone costs about -53; race play on `rows2.bin` alone costs about -2 (at most about 4 Elo within the confidence intervals).
- **Compared with search effort:** `BROWSER_SPEC` at 40,000 nodes beats `BEST_SPEC` at 20,000 by about +89 Elo (200 pairs per seed). By difference of the two matches, one doubling of the node budget is worth about 145 Elo to `BROWSER_SPEC`, so the table loss equals less than half a doubling (about 0.4). This figure is derived from two separate matches, not measured in one.
- **Network player:** unaffected; it always used `rows2.bin`.
- **Limits of the measurement:** 12.5 ms per move is far below a human time budget; the `rows2.bin` loss at seconds per move is not measured.
- **Ports:** the Java (TeaVM) and C++ (WebAssembly) browser ports need a table constructor from bytes that checks the manifest's SHA-256, plain synchronous array lookups and no memory mapping ([offline mode, what the browser ports need](../offline-mode.md#what-the-browser-ports-need)).

## Alternatives

Every variant that was examined, with its measured result. "Exact share" is the share of `rows1.bin` lookups in `BEST_SPEC` self-play that the variant answers exactly (k = 0: 44.8 %, k ≤ 1: 85.3 %, k ≤ 2: 98.1 %).

| Variant | Measured result | Outcome |
|---|---|---|
| **Option 1:** ship `rows1.bin` whole, cache it in OPFS or IndexedDB, inflate in the page | 254 MB raw; whole-stream gzip 50.8, zstd 27.4, xz 24.4 MB; gzip in independent 64 KB blocks 53.6 MB; 254 MB memory per tab | rejected: download and memory too large, zstd and xz need a WebAssembly decoder |
| **Option 2:** `rows2.bin` only (`BROWSER_SPEC`) | 2.45 MB gzip, 13.1 MB memory; about -56 Elo; exact share 45 % | **chosen** |
| **Option 3:** load only the 64 KB blocks of `rows1.bin` that games need, "unknown" elsewhere | `rows1.bin` has 3,879 blocks of 64 KB. `BEST_SPEC` games touch 2,678 of them (69 %) in 200 games and the set still grows; 99 % of lookups need 947 blocks (24 %). The top 776 blocks (20 %) chosen from the even-numbered games hit 97.1 % of lookups in the odd-numbered games, but 32.8 % of positions have at least one miss (31.3 % for the 99 % set of 850 blocks) | rejected |
| Block selection from random legal games (for option 3) | 200 random games (40,000 plies) produce 0 `rows1.bin` lookups; random games never reach a race | untestable, so unusable |
| **Option 4, strength pack:** `rows1.bin` only for k ≤ 2, layout L, mirror-folded; k = 0 taken from `rows2.bin` | `maxk:2`: about -1 Elo (+0.0 and -2.3); 90.5 MB raw, gzip 17.0, zstd 9.8, xz 8.6 MB | deferred; revisit conditions above |
| Strength pack for k ≤ 1 only (`maxk:1`) | about -18 Elo with the estimator fallback, about -19 with the `rows2.bin` fallback; folded 40.75 MB raw, gzip 7.74, zstd 4.21 MB | rejected: still a clear loss |
| Push estimator with one correction per k (`estimate`) in place of all `rows1.bin` lookups | about -61 Elo (-54.9 and -68.0); on random states exact 49.1 %, within 1 move 93.1 %, race decision agrees 85.0 % | rejected: no better than no value |
| Push estimator with one correction per row-1 pattern (`estimate2`, 256 bytes) | about -89 Elo (-81.4 and -96.2); on random states exact 51.6 %, within 1 move 95.2 %, race decision agrees 86.7 % | rejected: fits random states better and plays worse |
| Layout L for every codec | against the dense file: gzip 8 % smaller (46.98 against 50.84 MB), zstd and xz 4 to 6 % larger (29.00 against 27.38, 25.55 against 24.43 MB) | use layout L only for gzip, or for its index structure |
| Residual against the push estimator, then entropy-coded (thesis j in [offline mode](../offline-mode.md#theses-and-verdicts)) | order-0 entropy of the residual about 44 MB for the whole table | open, pessimistic; not built |

### Compressed sizes

Layout L unless stated, whole stream unless stated, MB = 10^6 bytes. Source: [offline mode, compressed sizes](../offline-mode.md#compressed-sizes).

| Part | Raw | gzip -9 | zstd -19 | xz -6 | gzip -9 in 64 KB blocks |
|---|---|---|---|---|---|
| `rows1.bin` as stored (dense rank order) | 254.19 | 50.84 | 27.38 | 24.43 | 53.62 |
| k = 0 | 13.12 | 2.45 | 1.27 | 1.13 | 2.59 |
| k ≤ 1 | 68.38 | 12.97 | 7.05 | 6.22 | 13.67 |
| k ≤ 2 | 155.41 | 29.26 | 16.81 | 14.79 | 30.78 |
| all k | 254.19 | 46.98 | 29.00 | 25.55 | 49.34 |
| k ≤ 1, mirror-folded | 40.75 | 7.74 | 4.21 | 3.71 | |
| k ≤ 2, mirror-folded | 90.48 | 17.01 | 9.83 | 8.63 | 17.88 |
| all k, mirror-folded | 141.04 | 26.04 | 16.10 | 14.16 | 27.33 |
| `rows2.bin` (ships) | 13.12 | 2.45 | 1.27 | | |

The k = 0 part differs from `rows2.bin` in 10,558 of 13,123,110 entries (about 0.08 %), always with `rows1.bin` one move shorter, so a pack never ships it twice. Compressing in independent 64 KB blocks, which allows random access, costs gzip about 5 % (53.62 against 50.84 MB dense, 49.34 against 46.98 MB in layout L).

## Open theses

### l: A learned model can reproduce rows1.bin exactly, with random access

**Origin:** stated by the project owner on 2026-11-18, after the measurements; translated from a German original that is not in the repository:

> If rows1 compresses well with zstd, then a kNN (or another learned model) of similar size to the packed variant can reproduce the table exactly, with random access; zstd gives sequential access only.

**Status:** open, untested.

**Falsifiable form.** The thesis holds for a part of `rows1.bin` (the whole table, or the k ≤ 2 mirror-folded strength pack) if a model M with an exception list E meets all four criteria:

1. **Exact:** M corrected by E returns the `rows1.bin` value for every configuration of the part, checked on all of them, not on a sample.
2. **Size:** the bytes of M, E and any index together are at most the zstd -19 size of the same part: 27.38 MB for the dense whole table, 9.83 MB for k ≤ 2 mirror-folded.
3. **Random access:** one lookup reads the parameters of M and at most one small block of E of fixed size (for example 4 KB). It does not decode a stream from its start. For comparison, the existing random-access form is block compression: gzip in independent 64 KB blocks costs about 5 % more than the whole stream (zstd in blocks was not measured).
4. **Cost:** the lookup is fast enough for the leaf evaluator. `BEST_SPEC` self-play made 12,177,214 `rows1.bin` lookups in 200 games (about 610 per ply, about 317 of them at leaves). As a threshold, this ADR borrows the one of thesis h in [offline mode](../offline-mode.md#theses-and-verdicts): the lookups add less than 20 % move time in a time-limited match.

A failed experiment refutes the thesis only for the model class that was tried.

**Evidence (not a verdict):**

- The push estimators are exact on only 49 to 52 % of random configurations and within one move on 93 to 95 %. In real `BEST_SPEC` games the plain push estimator is exact for k = 1 in only 43.3 % of lookups (56.9 % on random states). If that rate carried over to a model, its exception list would hold about half of all entries; the model must be far more exact than these estimators for the size criterion to hold.
- A better fit on random states played worse (`estimate2` against `estimate`). Exactness, not fit quality, is what the thesis needs, so this does not refute it, but it shows that random-state statistics mislead here.
- The order-0 entropy of the residual of `rows1.bin` minus the push estimator is about 44 MB, more than the 27.38 MB zstd achieves. This figure is unverified and pessimistic: order 0 ignores all context that a model could use.
- The table is a function of a set of ten stones on 36 cells, with exact mirror symmetry and the structure of a breadth-first search (each count is one more than the best successor). A structured model, value = push estimate + a small learned correction, with the remaining exceptions stored, is therefore the realistic form of the thesis.
- A plain kNN over neighbours in rank order is not obviously better than zstd: zstd's context modelling already exploits the similarity of nearby ranks. A kNN also needs a stored neighbour set and a search per lookup.

The thesis may be true; this ADR records it as open.

**Deciding experiment (not run):** fit a structured model on the k ≤ 2 mirror-folded part (90.48 MB raw), count the entries it gets wrong, encode those exceptions in fixed-size blocks, and compare M + E in bytes with 9.83 MB (zstd, whole stream) and 17.88 MB (gzip in 64 KB blocks). If the size criterion holds, measure the lookup time in the WebAssembly build.

## References

- [Offline mode](../offline-mode.md): method, raw results, theses a to l, strength pack sketch, reproduction commands.
- [Endgame and opening](../endgame-and-opening.md): race tables and race play.
- Commits merged in `d8f68c2`: `11779cb` (`BROWSER_SPEC`, rows1 request counter, `BrowserSpecTest`), `a36eb86` (`pack-tables.py`), `8fc7608` (C++ `BROWSER_SPEC` and tests), `3addaf4` (race table seam and push estimator), `960829f` (rank log), `48b2e69` (`maxk` variants in the arena), `9a3b1cf` (`estimate2`), `450eeff` (`LayoutLTool`, compressed sizes), `272b6ec` (offline mode document).
