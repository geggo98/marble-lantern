# Rules of the game

**TL;DR:** This document fixes the rules that the engine implements. [Sturtevant2019, §3] defines a complete two-player rule set: a player wins when the goal is filled and holds at least one own piece (Definition 1), two kinds of states are illegal (Definitions 2 and 3), and the first repeated position draws the game (Definition 4). The paper does not forbid a piece to leave its goal. The project default (**project decision**) uses Definition 1, ends a game without a winner after 200 plies and scores that game by distance to the goal. Two defaults deviate from the paper on purpose (**project decision**): `leaveGoal = FORBIDDEN` and `repetition = MOVE_LIMIT`. The project uses the paper only to verify its rules; it does not try to solve any variant. All variants are switches of one immutable `RuleSet` record. The jump rule is one of these switches: the default `ADJACENT` jumps over a neighbour, the optional `LONG` is the Halma long hop (see [Jump rule](#jump-rule)). The engine must pass the conformance tests at the end of this document before anyone writes search code.

Chinese Checkers is called Stern-Halma in German. This document uses the name Chinese Checkers.

## How to read this document

Each claim about the literature and each project choice has one of three levels. The level is always visible. Geometry and arithmetic that anyone can recompute have no marker.

| Level | Meaning | Marker |
|---|---|---|
| Established | A cited source supports the claim. | A citation key, with the section where one exists, e.g. [Sturtevant2019, §3.2]. Full entries are in [references](references.md). |
| Reported | The project owner relayed the claim from a literature survey. Nobody has checked it against the source yet. | The word **reported**; "source not located" when a check found no source. |
| Project decision | The project chose this. No source makes it true. | The words **project decision**. |

Two markers qualify a project decision. **Proposed** means that the owner has not confirmed it yet. **Open point** means that nothing is decided yet. A third marker, **project reasoning**, marks a conclusion that the project draws itself, without a source.

## Scope

This document covers:

- the board, the coordinates and the start layouts for 2 and 3 players,
- legal moves, the goal and the win condition,
- the rule switches and their default values,
- draws, three-player specifics and symmetry,
- the test values that the engine must reproduce, and the optional, later oracles from [Sturtevant2019].

This document does not cover search, evaluation, the endgame database or the opening book. See [search and evaluation](search-and-evaluation.md), [endgame and opening](endgame-and-opening.md) and [architecture](architecture.md).

**Project decision:** the rules are written down here before the engine code exists (phase 0). If the code and this document disagree, the code is wrong until this document changes.

## Board and coordinates

The board is a six-pointed star with 121 cells [MITSlides2010]. [Sturtevant2019, §3] describes a star-shaped board with six corners and 10 pieces per player; it does not give the cell count. The board has two parts:

- a central hexagon with 61 cells,
- six corner triangles with 10 cells each (rows of 1, 2, 3 and 4 cells).

61 + 6 × 10 = 121.

The engine uses cube and axial hex coordinates as described in [RedBlobHex]. A cube coordinate is a triple (q, r, s) with q + r + s = 0. The axial form stores only (q, r) and computes s = −q − r. Each cell has up to six neighbours. The six direction vectors are (+1, 0, −1), (−1, 0, +1), (0, +1, −1), (0, −1, +1), (+1, −1, 0) and (−1, +1, 0).

**Project decision:** the board is the set of cells that satisfies at least one of these two conditions:

- max(q, r, s) ≤ 4,
- min(q, r, s) ≥ −4.

Each condition describes one large triangle with 91 cells. The two triangles overlap in the hexagon with |q|, |r|, |s| ≤ 4 (61 cells). The union has 91 + 91 − 61 = 121 cells. The six corners are the cells with q > 4, q < −4, r > 4, r < −4, s > 4 or s < −4. The corner opposite to "q > 4" is "q < −4", and so on.

The engine stores the board as a cell index from 0 to 120 with precomputed neighbour tables (6 directions × 121 cells). Each player's pieces are a 121-bit set in two `long` values. The [architecture](architecture.md) document describes this in detail.

## Pieces and start layouts

In the standard game each player has 10 pieces. At the start, a player's pieces fill one corner triangle. The goal of each player is the opposite corner. In the two-player game the players start at the top and at the bottom, and each goal is the corner where the opponent started [Sturtevant2019, §3].

| Players | Occupied corners | Goal |
|---|---|---|
| 2 | Two opposite corners, e.g. r < −4 and r > 4. | The opposite corner, which is the other player's start corner. |
| 3 | Every second corner around the star. | The opposite corner. That corner is empty at the start. |

For 3 players, the occupied corners alternate with empty corners around the star. Each player's goal is the empty corner opposite to their start corner. This layout is from the owner's survey (**reported**).

Around the star, the corners come in this order: q > 4, s < −4, r > 4, q < −4, s > 4, r < −4. The three corners r < −4, q < −4 and s < −4 are therefore every second corner. Their goals r > 4, q > 4 and s > 4 are the three empty corners. **Project decision (proposed):** the three-player game uses the start corners r < −4, q < −4 and s < −4. This keeps the two-player start corner r < −4 for player 0.

### Screen orientation and clockwise order

"Clockwise" needs a picture of the board. **Project decision:** the engine draws the board with the pointy-top layout of [RedBlobHex]: a cell (q, r) has the screen position x = √3 · (q + r/2), y = 3/2 · r, with x to the right and **y downwards**. In this picture r < −4 is the top corner and r > 4 is the bottom corner.

The centre of each corner then has these screen angles, measured from the x axis (computed by the project, 2026-11-07):

| Corner | Position on screen | Angle |
|---|---|---|
| q > 4 | upper right | −30° |
| s < −4 | lower right | 30° |
| r > 4 | bottom | 90° |
| q < −4 | lower left | 150° |
| s > 4 | upper left | 210° |
| r < −4 | top | 270° |

With y downwards, a rising angle turns clockwise on the screen. The corner order above is therefore the clockwise order.

**Project decision** (owner, 2026-11-07): three players move clockwise around the star. Player 0 starts at r < −4 (top), player 1 at s < −4 (lower right), player 2 at q < −4 (lower left). Player 0 moves first. Two players alternate, so the direction has no effect for them.

The counter-clockwise order (r < −4, q < −4, s < −4) gives an equivalent game. The left-right mirror (q, r, s) → (s, r, q) keeps the corner r < −4, swaps the corners q < −4 and s < −4, swaps their goals q > 4 and s > 4, and maps every legal move to a legal move. The mirror therefore turns one order into the other. Only the player labels change.

The engine takes the corner assignment and the play order from the `RuleSet` and does not hard-code them. A conformance test checks that no two occupied corners are neighbours around the star, that each goal is empty at the start, and that the play order is clockwise.

## Legal moves

A move takes one own piece from a start cell to an end cell. There are two kinds of move.

1. **Step.** The piece moves to an empty neighbour cell.
2. **Jump chain.** The piece jumps over an occupied neighbour cell (own or opponent) and lands on the empty cell directly behind it, on the same line. After a jump, the piece may jump again from the landing cell. The chain can have any length and any direction, including backwards. The player can stop after any jump.

This section describes the default `jumpRule = ADJACENT`. The section [Jump rule](#jump-rule) defines the optional long hop.

A move cannot mix the two kinds. A step ends the move. A jumped piece stays on the board; jumping does not capture. [Sturtevant2019, §3.1] defines the same two kinds of move: a piece can take any number of hops in a turn and can stop when further hops are still possible, and hopped pieces stay on the board. The paper also notes that the game is not acyclic: a position can repeat without progress.

[Sturtevant2019, §3] says that pieces are typically not allowed to move into the four corners that are neither the start nor the goal corner. Some rules allow a piece to pass through them if it does not stay there at the end of the turn. The paper's two-player implementation does not allow this; a footnote says that the passage rule may be more useful with more than two players. **Open point:** this document does not yet fix the side-corner rule for the 121-cell board. The rule has no effect on the first two plies (see [Move counts on the full board](#move-counts-on-the-full-board)).

The path of a jump chain has no effect on the result. Only the pair (start cell, end cell) matters. The move generator therefore returns each (start, end) pair exactly once, even when several jump paths reach the same end cell. **Project decision:** a jump chain that returns to its start cell is not a move.

**Project decision:** the move generator finds jump chains by a graph search over jump targets with a visited mask (depth-first with an explicit stack in the engine). This makes deduplication automatic.

In the mid-game, the branching factor is often well above 50, because every end cell of a jump chain counts as its own move (**reported**).

```mermaid
flowchart LR
    A[Start cell] -->|step| B[Empty neighbour]
    A -->|jump| C[Landing cell 1]
    C -->|jump| D[Landing cell 2]
    A -->|other jump path| E[Landing cell 3]
    E -->|jump| D
    D -.->|same pair start, end| M["One move: (A, D)"]
```

## Goal and win condition

A player wins when the goal is filled. The switch `blockadeRule` defines "filled":

- `OPPONENT_FILLS` (default): every goal cell holds a piece, and at least one of these pieces is the player's own. This is Definition 1 of [Sturtevant2019, §3.2]. A goal that holds only opponent pieces is not a win.
- `NONE`: every goal cell holds one of the player's own pieces.

The first player to fill the goal wins, and the game ends. This also holds for three players (see [Three-player specifics](#three-player-specifics)). Section [Draw handling](#draw-handling) describes the end of a game without a winner.

[Sturtevant2019, §3.2] notes a side effect of Definition 1: a player who leaves pieces in the own start corner lets the opponent fill the goal around them. On very small boards this gives a win after 3 plies, which the paper accepts. The paper rejects the rule "imprisoned marbles forfeit the game" as inadequate, because a piece on the outer row of the goal cannot be imprisoned and still blocks the goal.

## The contested rules and why they matter

Different rule books answer three questions in different ways:

1. May a player keep a piece in the start corner to block the opponent's goal?
2. May a piece leave the goal area again after it has entered it?
3. What happens when a position repeats?

These questions are not cosmetic. A player can leave one piece in the start corner and so keep the opponent from ever filling the goal; rule books typically do not handle this [Sturtevant2019, §3.2]. Positions can repeat without progress [Sturtevant2019, §3.1], so a game can go on forever unless a rule ends it. A program that searches or solves the game needs a complete rule set, or the value of a position is not defined.

[Sturtevant2019] addresses exactly this. Its abstract says that it gives "a complete set of rules needed to solve the game". The paper states that, to the author's knowledge, the possible values of Chinese Checkers states had not been determined before, and that the author found no rules that discuss drawn positions [Sturtevant2019, §3.4].

### What [Sturtevant2019] defines

The paper states its rules for the two-player game [Sturtevant2019, §3]:

| Rule | Content | Source |
|---|---|---|
| Definition 1 (win) | A state is won for player n if n's goal area is filled with pieces and at least one of these pieces belongs to n. | §3.2 |
| Definition 2 (illegal, part 1) | A state is illegal for player n if n's winning condition is met and it is n's turn to move. A move that hands the opponent the win is therefore illegal. | §3.3 |
| Definition 3 (illegal, part 2) | A state is illegal if one or more empty cells in player n's goal area are unreachable for n because of other players' pieces. With six pieces it suffices to check the two outer edges of the goal and its tip; with 10 pieces "the conditions are more complicated". | §3.3 |
| Definition 4 (draw) | A game is drawn if any board state is repeated during play. One repetition suffices. | §3.4 |

The paper has no rule that forbids a piece to leave its goal area, and it has no move limit. It says that Definitions 2 and 3 are only necessary in variants with more pieces, and it admits that it may miss cases that matter in human play [Sturtevant2019, §3.3].

## Rule switches

The `RuleSet` record is immutable. Each switch has a fixed set of allowed values.

| Switch | Allowed values | Default | Effect | Relation to [Sturtevant2019] |
|---|---|---|---|---|
| `players` | 2, 3 (later 4 and 6) | 2 | Number of players and start layout. | The paper focuses on two players (§3). |
| `moveLimit` | positive number of plies; off only in the small-board mode | 200 for 2 players; 300 for 3 players (**proposed**) | The game ends without a winner after this many plies. The limit applies with both `repetition` values. | The paper has no move limit. |
| `leaveGoal` | `FORBIDDEN`, `ALLOWED` | `FORBIDDEN` | With `FORBIDDEN`, a piece in its goal area must not move to a cell outside the goal area. | Deviation: the paper allows leaving the goal. |
| `blockadeRule` | `NONE`, `OPPONENT_FILLS` | `OPPONENT_FILLS` | With `OPPONENT_FILLS`, opponent pieces in the goal count as filling it, if at least one own piece is in the goal. | Same as Definition 1 (§3.2). |
| `suicideMoves` | `ILLEGAL`, `LEGAL` | `ILLEGAL` (**proposed**) | With `ILLEGAL`, a move that meets the opponent's winning condition is not legal. | Same as Definition 2 (§3.3). |
| `blockingStates` | `NONE`, `ILLEGAL` | `NONE` (**proposed**) | With `ILLEGAL`, a move to a state with an empty goal cell that its owner cannot reach is not legal. | `ILLEGAL` is Definition 3 (§3.3). The default deviates from the paper. |
| `repetition` | `MOVE_LIMIT`, `REPETITION_IS_DRAW` | `MOVE_LIMIT` | With `REPETITION_IS_DRAW`, the first repeated position also ends the game without a winner. | Deviation: the paper uses Definition 4 (§3.4). |
| `drawHandling` | `DRAW`, `SCORE_BY_DISTANCE` | `SCORE_BY_DISTANCE` | The result when the game ends without a winner. | The paper scores such games as draws. |
| `ranking` | `FIRST_WINNER_ONLY`, `FULL_RANKING` | `FIRST_WINNER_ONLY` | With `FIRST_WINNER_ONLY`, the game ends when the first player fills the goal. With `FULL_RANKING` and three players, that player is finished and the others play on (see [Playing on for second place](#playing-on-for-second-place)). With two players, both values behave the same. | The paper focuses on two players (§3) and gives no rule for ranking. |
| `jumpRule` | `ADJACENT`, `LONG` | `ADJACENT` | With `LONG`, a piece hops over one piece at any distance along a line and lands at the same distance behind it (see [Jump rule](#jump-rule)). | The paper plays adjacent jumps only (§3.1). `LONG` is outside the paper, so its oracles do not apply. |

### Project defaults and their evidence

- **`moveLimit`** — **project decision** (owner, 2026-11-07): 200 plies for two players. The value for three players, 300 plies (100 per player), is **proposed**.
- **`blockadeRule = OPPONENT_FILLS`** — **project decision** (owner, 2026-11-07): an opponent piece in the goal counts as filling it if at least one own piece is in the goal. This is exactly Definition 1 [Sturtevant2019, §3.2]. A goal that holds only opponent pieces is not a win.
- **`drawHandling = SCORE_BY_DISTANCE`** — **project decision** (owner, 2026-11-07). The value `DRAW` stays available, because comparisons with the solved tables of [Sturtevant2019] need real draws (see [Oracles from the paper](#oracles-from-the-paper)).
- **`ranking = FIRST_WINNER_ONLY`** — **project decision** (owner, 2026-11-07). It stays the default. `FULL_RANKING` was added on 2026-11-19 at the owner's wish (see [Playing on for second place](#playing-on-for-second-place)).
- **`suicideMoves = ILLEGAL`** — **project decision (proposed)**. It follows Definition 2. The paper focuses on the two-player game [Sturtevant2019, §3]; the three-player form of the rule is an open point.
- **`blockingStates = NONE`** — **project decision (proposed)**. The project deviates from Definition 3 in the default, because the paper itself calls the conditions for 10 pieces "more complicated" [Sturtevant2019, §3.3] and does not give them. Before `ILLEGAL` can be implemented for 10 pieces, the project must define "unreachable" exactly.

### Deliberate deviations from [Sturtevant2019]

The project uses the paper only to verify its rules. Two defaults deviate from it on purpose:

- **`leaveGoal = FORBIDDEN`** — **project decision**; differs from [Sturtevant2019, §3], which allows a piece to leave its goal.
- **`repetition = MOVE_LIMIT`** — **project decision**; differs from [Sturtevant2019, §3.4], which draws the game at the first repeated position (Definition 4). The 200-ply limit applies as a backstop in both modes.

### Details that this document fixes for the engine

- **`leaveGoal = FORBIDDEN`** checks only the end cell of a move. A jump chain may pass through cells outside the goal, because the path has no effect (see [Legal moves](#legal-moves)). **Project decision (proposed).**
- **`moveLimit`** counts plies from the start position. A move that wins on the last allowed ply is a win. After that ply, the game ends without a winner. **Project decision (proposed).**
- **`suicideMoves = ILLEGAL`**: a move that meets the opponent's winning condition is not legal, even if it also fills the mover's own goal. **Project reasoning:** Definition 2 makes the resulting state illegal for the opponent, who is to move; the definition does not exempt the mover's own win.

## Jump rule

**Project decision** (owner request, game-variants feature, 2026-11): the engine supports two jump rules through the switch `jumpRule` (Java enum `RuleSet.JumpRule`). The default stays `ADJACENT`, the rule of [Legal moves](#legal-moves). `LONG` is the Halma long hop (German "Fernsprung").

### Definition

- **`ADJACENT`:** a piece jumps over one adjacent piece to the cell directly behind it.
- **`LONG`:** a piece on cell X looks along each of the six directions. P is the first piece (own or opponent) on that line, at distance k ≥ 1 steps from X. The landing cell L is the mirror image of X across P: k steps behind P, 2k steps from X. The hop is legal when all three conditions hold:
  1. the k − 1 cells strictly between X and P are empty;
  2. the k − 1 cells strictly between P and L, and L itself, are on the board and empty;
  3. the player may stand on L. This is the same allowed-cell rule as for `ADJACENT`: the central hexagon, the own start corner and the own goal corner.

The moving piece's own start cell counts as empty during the whole move. Because P is the first piece on the line, each direction gives at most one landing.

Example on the row r = 0 of the central hexagon, in axial coordinates (q, r): a piece on X = (−3, 0), a piece on P = (−1, 0), and the cells (−2, 0), (0, 0) and (1, 0) empty. Then k = 2, and the piece hops to L = (1, 0). Under `ADJACENT` the same piece has no jump in this direction, because (−2, 0) is empty.

Chains of hops work as for `ADJACENT`: any number of hops, in any direction, with a stop after any hop. Only the pair (start cell, end cell) counts, the move generator returns each pair once, and a chain that returns to its start cell is not a move. Steps do not change. `leaveGoal`, `suicideMoves` and the other switches act on the end cell as before.

### Relation between the two rules

A hop with k = 1 is exactly the `ADJACENT` jump. The `LONG` move set therefore contains the `ADJACENT` move set; a seeded property test on random positions checks this. From the start position, the perft leaf counts (number of move sequences of a given depth) are:

| Depth | `ADJACENT` | `LONG` |
|---|---|---|
| 1 | 14 | 14 |
| 2 | 196 | 196 |
| 3 | 4,648 | 4,872 |

**Project reasoning:** a hop lands on the mirror cell L = 2P − X. Every landing therefore has the same coordinate parity (q mod 2, r mod 2) as the start cell of that hop. A landing is never a neighbour of its start cell, because a neighbour differs in parity. So no (start, end) pair can arise both as a step and as a hop.

### Consequences for the rest of the project

Each item is a **project decision**. The reason is the same for all: the components were built and tuned for single jumps.

- **No race table.** The race table (the tablebase behind `race=1` and `race=2`) and the solitaire beam are computed for single jumps and are invalid under `LONG`. A player spec with `jump=long` together with `race=1`, `race=2` or a race evaluator `racelin*` is rejected with an `IllegalArgumentException`.
- **Weaker evaluation.** The evaluators `linear*`, `distance` and `stage4` stay usable but play weaker under `LONG`. Their jump features still count single jumps, and the distance table counts steps.
- **Search margins.** The futility margins of the search were tuned for single jumps.
- **Guard.** A player built for one jump rule refuses a position of the other rule.
- **Browser spec.** `Strategies.BROWSER_LONG_SPEC` is the browser spec without race play, with the evaluator `linear-r3` and `jump=long`.

**Open point:** the playing strength of `LONG` players is not measured.

The golden file `engine/src/test/resources/long-jump-golden.tsv` pins the rule for ports to other languages. It holds 30 positions, each with its move count and the SHA-256 of the sorted move list.

## Draw handling

A game ends without a winner in one of two ways:

- **Move limit (both modes):** the game reaches `moveLimit` plies (default 200 for two players). The limit is a backstop: it also applies with `repetition = REPETITION_IS_DRAW`.
- **Repetition (only with `repetition = REPETITION_IS_DRAW`):** a position repeats for the first time. This is Definition 4 [Sturtevant2019, §3.4]. **Project decision:** a position includes the side to move. Definition 4 speaks of a repeated "board state" and does not say whether the side to move is part of it.

`drawHandling` then gives the result:

- `SCORE_BY_DISTANCE` (default, **project decision**, owner 2026-11-07): each player gets a score from the remaining distance of the own pieces to the goal. The player with the smaller distance scores better. Equal distances give equal scores (**proposed**). **Project decision:** the distance measure is the single-agent distance table described in [search and evaluation](search-and-evaluation.md).
- `DRAW`: every player gets the draw value. This is how [Sturtevant2019] scores a game without a winner. Use it in the small-board mode and in every comparison with Table 1 of the paper.

```mermaid
stateDiagram-v2
    [*] --> Playing
    Playing --> Won: a player meets the win condition (Definition 1 by default)
    Playing --> NoWinner: moveLimit reached (default 200 plies)
    Playing --> NoWinner: position repeated (only with REPETITION_IS_DRAW)
    NoWinner --> Scored: drawHandling = SCORE_BY_DISTANCE (default)
    NoWinner --> Draw: drawHandling = DRAW
    Won --> [*]
    Scored --> [*]
    Draw --> [*]
```

## Three-player specifics

With three players the game is not zero-sum. There is no unique game value, only equilibria (**reported**). The kingmaker problem appears: a player who can no longer win can still decide which of the other two players wins (**reported**).

Consequences for the engine:

- The result of a game is a vector with one value per player, not a single number.
- Alpha-beta search does not apply. See [search and evaluation](search-and-evaluation.md) for Max^n, Paranoid, Best Reply Search and MCTS.
- **Project decision** (owner, 2026-11-07): play goes clockwise (see [Screen orientation and clockwise order](#screen-orientation-and-clockwise-order)), and the game ends when the first player fills the goal (`ranking = FIRST_WINNER_ONLY`). With `ranking = FULL_RANKING` the game goes on for second place; see [Playing on for second place](#playing-on-for-second-place).
- **Project decision (proposed):** `moveLimit = 300` plies for three players, which is 100 plies per player.
- [Sturtevant2019, §3] focuses on the two-player game. **Open point:** the three-player form of Definitions 2 and 3.

## Playing on for second place

**Project decision** (owner wish, 2026-11-19): with `ranking = FULL_RANKING`, a three-player game does not end at the first winner. The players who are left play on for second place. The rule applies to three players only. With two players, `FULL_RANKING` behaves exactly like `FIRST_WINNER_ONLY`.

- **Finishing.** After each move, every player still in play whose goal is filled under Definition 1 finishes and takes the next free rank. The check runs in turn order, starting with the player after the mover and ending with the mover. At most `players - 1` players finish this way; the last player in play takes the last rank when the game ends.
- **Finished players.** A finished player makes no more moves, and the turn order skips it. Its pieces stay on the board: they block cells and can be jumped over. Its goal no longer counts for Definition 2, so a move that fills it is not suicidal.
- **End of the game.** The game ends when only one player is still in play. Then the ranking is complete, and the reason is `WIN`. It also ends at the move limit, at a repeated position with `repetition = REPETITION_IS_DRAW`, or when the player to move has no legal move (`NO_MOVES`). In these cases the finished players keep their ranks, and the others are ranked by their distance sum, the smaller sum first; equal sums go to the lower player number. This mode has no draw.
- **Values.** The ranks are worth 1, 0 and −1. In a game that ends without a complete ranking, the players without a rank share the remaining rank values: evenly with `drawHandling = DRAW`, or with `SCORE_BY_DISTANCE` the even share plus (mean distance sum of the players without a rank − own distance sum) divided by the largest possible distance sum.
- **Position identity.** The hash and the equality of a position include the set of finished players. A position before a finish therefore never repeats a position after it. A game record is only the list of moves, so a replay needs the rule set with its `ranking` value.

The three-player search handles finished players as described in [search and evaluation](search-and-evaluation.md#value-scalar-for-two-players-vector-for-three).

## Symmetry

[Sturtevant2019, §3.5] names two symmetries of the two-player game:

1. **Left-right symmetry:** flipping the board left to right gives a position with the same value. Some positions are identical to their image, so the saving is close to, but below, a factor of 2. On the 6×6 board with 6 pieces the paper's implementation reduces the number of stored states by a factor of 1.998.
2. **Player symmetry:** flipping the board top to bottom, swapping the colours and swapping the player to move gives a symmetric position.

In the coordinates above, with the players on the r axis (top r < −4, bottom r > 4), the two symmetries have these forms. The screen positions in [Screen orientation and clockwise order](#screen-orientation-and-clockwise-order) confirm them: x depends on q − s, and y depends on r.

| Symmetry | Map of cells | Other changes |
|---|---|---|
| Left-right flip | (q, r, s) → (s, r, q) | none; each player keeps its corners |
| Player symmetry | (q, r, s) → (−s, −r, −q) | colours swapped, side to move swapped |

**Project decision:** the engine also uses the 180-degree rotation (q, r, s) → (−q, −r, −s) with a colour swap and a swap of the side to move. This rotation is the project's own definition, not a symmetry that the paper names. It equals the player symmetry followed by the left-right flip, so it adds no new equivalence class. Opening book keys and test counts use these symmetries.

For three players the left-right flip changes the play order (see [Screen orientation and clockwise order](#screen-orientation-and-clockwise-order)). **Open point:** which symmetries the three-player engine uses.

## Move counts on the full board

The owner's survey relayed three figures for the start position: 14 legal first moves, 7 of them up to symmetry, and \|L2\| = 98 (**reported**, source not located). These figures are **not** in [Sturtevant2019]. The survey does not define \|L2\|.

The paper says only this about first moves: "There are six possible adjacent moves at the beginning of the game" [Sturtevant2019, §3.1]. The sentence refers to the start position in the paper's Figure 2, and it counts steps only, no hops. The text does not say which board size Figure 2 shows. The sentence is therefore not a figure for the 121-cell board, and this document does not use it as one.

**Project decision:** the engine's own move generator computes the full-board counts. A test fixes a value only after two independent counts agree. A first count by a project script (2026-11-07, rules of this document, two players at r < −4 and r > 4) gives:

- 14 legal first moves,
- 7 classes of first moves under the left-right flip,
- 196 distinct positions after two plies, which is 98 classes under the left-right flip.

The script gets the same numbers whether pieces may enter the four side corners or not. The count agrees with the reading "\|L2\| = number of positions after two plies, up to the left-right flip".

**Update (2026-11-07):** the engine's move generator and an independent reference generator in the test code (axial coordinate arithmetic, recursive jump search, no shared tables) both give 14 first moves, 7 classes, 196 positions after two plies and 98 classes. Three independent counts now agree, so tests 4 to 6 are no longer provisional. They stay values computed by the project, not oracles from the literature.

## Oracles from the paper

Table 1 of [Sturtevant2019, §5] gives exact counts for strongly solved small boards. The board is the central m × m diamond without corners, and each player has k pieces [Sturtevant2019, §3]. The table as printed in the paper:

| Board | Pieces per player | Positions | Wins | Draws | Illegal | Start position |
|---|---|---|---|---|---|---|
| 7×7 | 1 | 4,704 | 2,304 | 0 | 96 | first-player win |
| 7×7 | 2 | 2,542,512 | 1,265,851 | 0 | 10,810 | first-player win |
| 7×7 | 3 | 559,352,640 | 279,297,470 | 180,860 | 576,840 | first-player win |
| 7×7 | 4 | 63,136,929,240 | 31,532,340,944 | 51,686,042 | 20,561,310 | first-player win |
| 4×4 | 6 | 3,363,360 | 1,205,441 | 547,058 | 405,420 | first-player win (whole row known erroneous per [SturtevantPubs]; not an oracle) |
| 5×5 | 6 | 9,610,154,400 | 4,749,618,788 | 47,056,118 | 63,860,706 | first-player win |
| 6×6 | 6 | 2,313,100,389,600 | 1,153,000,938,173 | 5,199,820,604 | 1,898,692,650 | first-player win |

How to read the table, according to [Sturtevant2019, §5]:

- "Wins" counts the won states of one player. The game is symmetric between the players, so the losses have the same count.
- The counts are for the full game and ignore symmetry.
- All rows except 6×6 with 6 pieces were solved several times with different solvers. The paper adds: "The exact count of wins should not be considered correct until this verification has been completed."
- Table 1 also lists 7×7 with 6 pieces per player, 170,503,381,976,928 positions, without a result. The paper names this board as its next goal.

The author's publications page adds a later note to the paper [SturtevantPubs]: "The 6x6 results in Table 1 have now been verified with a different solver. The 4x4 board with 6 piece numbers appears to contain a copy/paste error and are not consistent with any current or historical implementation of the code. An updated table will be provided shortly." The project therefore treats the rows as follows:

- **4×4, 6 pieces:** not an oracle. Do not use this row.
- **6×6, 6 pieces:** confirmed by a second solver, according to the author's note.
- **7×7 with 1 to 4 pieces, 5×5 with 6 pieces:** as printed in the paper; the note does not mention them.

Check [SturtevantPubs] for the promised updated table before a test relies on any row.

**Project reasoning:** the counts follow the paper's own rules, Definitions 1 to 4, because the paper sets up these rules to solve the game. The paper does not say this next to Table 1. An engine can only expect to reproduce the counts in a mode with exactly these rules.

**Project reasoning:** the "Positions" column counts every placement of the pieces on the m × m cells, times 2 for the side to move, including illegal states. The project recomputed three rows (2026-11-07):

- 7×7, 1 piece: 49 × 48 × 2 = 4,704.
- 7×7, 2 pieces: C(49, 2) × C(47, 2) × 2 = 2,542,512.
- 4×4, 6 pieces: C(16, 6) × C(10, 6) × 2 = 3,363,360. The "Positions" figure of this row fits the formula; the author's note does not say which numbers of the row are wrong, so the row stays excluded.

**Project reasoning:** on 7×7 with 1 piece the illegal count also follows by hand, if the goal of a one-piece game is the single tip cell. Under Definition 2 a state is illegal when the player to move already stands on the own goal cell. Each player has 48 such states, and 2 × 48 = 96.

### Small-board mode

**Project decision (proposed, not scheduled):** a small-board mode of the engine as an optional, later conformance target for Table 1. It tests only the engine's counting rules; the project does not try to solve any board. It uses:

- the m × m diamond without corner cells and k pieces per player,
- the exact rules of [Sturtevant2019]: `leaveGoal = ALLOWED`, `blockadeRule = OPPONENT_FILLS`, `suicideMoves = ILLEGAL`, `blockingStates = ILLEGAL`, `repetition = REPETITION_IS_DRAW`, `drawHandling = DRAW`, no move limit.

The mode checks the counts that come from enumeration alone: "Positions" and "Illegal", first for the 7×7 rows with 1 and 2 pieces (the 4×4 row is excluded, see [Oracles from the paper](#oracles-from-the-paper)). "Wins" and "Draws" would need a retrograde solver for the whole state space. That is solving the board, and the project does not plan it. For six pieces the paper gives a simple test for Definition 3: check whether the two outer edges of the goal are occupied and the tip is empty [Sturtevant2019, §3.3]. **Open point:** the start layouts for each piece count. The paper shows them only in its figures, not in the text.

## Status of the game

- **Full board:** [Sturtevant2019] reports no solution for the full board. It says that one common board size, with 1.73 × 10^24 states, "might be weakly solvable, given a good proof strategy" (§1), and it names 7×7 with 6 pieces per player as its next goal (§5).
- **Small boards:** the two-player game is strongly solved on boards up to 6×6 with 6 pieces per player. All these small variants are first-player wins [Sturtevant2019, §5, Table 1].
- **Solitaire:** with one player alone on the board, [Bell2009] proves that a 27-move solution is optimal. According to [Bell2009], Levenspiel found this solution in 1971. No shorter solution exists. The [endgame and opening](endgame-and-opening.md#the-27-move-solitaire-pattern-as-a-template) document explains how the project uses it and why it proves nothing against an opponent.

## Conformance tests

The engine must pass all of these tests before anyone writes search code. **Project decision** (owner, 2026-11-07): the first search code is the two-player alpha-beta baseline with the single-agent distance heuristic; the network and MCTS come later (see [architecture](architecture.md)). Each test runs with the default `RuleSet` unless the test names a switch.

1. The board has 121 cells: 61 in the hexagon and 10 in each of the six corners.
2. Each neighbour relation is symmetric. Each cell has between 2 and 6 neighbours.
3. Each player starts with 10 pieces in the start corner. For 2 players the corners are opposite. For 3 players no two occupied corners are neighbours around the star, each goal is empty, and the play order is clockwise: r < −4, then s < −4, then q < −4.
4. The move generator computes the number of legal first moves from the start position. The expected value is 14 (computed by a project script, see [Move counts on the full board](#move-counts-on-the-full-board)). Confirmed by the engine and by an independent reference generator (2026-11-07).
5. Under the left-right flip, the first moves form 7 classes. Confirmed, as test 4.
6. After two plies there are 196 distinct positions, or 98 up to the left-right flip. Confirmed, as test 4. The figure "\|L2\| = 98" from the owner's survey is not in [Sturtevant2019] and is not an oracle.
7. The move list has no duplicate (start, end) pair. Test this on a position where two jump paths reach the same end cell.
8. A jump chain can turn backwards. Test it on a position where the only path to an end cell goes away from the goal.
9. With `leaveGoal = FORBIDDEN`, no move takes a piece from inside its goal to a cell outside the goal. With `ALLOWED`, such a move exists in a constructed position.
10. With `blockadeRule = OPPONENT_FILLS` (Definition 1), three constructed positions give these results:
    - goal full, at least one own piece, the other cells opponent pieces: win;
    - goal full, only own pieces: win;
    - goal full, only opponent pieces: not a win.

    With `NONE`, only the position with only own pieces is a win.
11. With `suicideMoves = ILLEGAL`, a move that fills the opponent's goal under the active `blockadeRule` is not in the move list. With `LEGAL`, the same move exists, and the opponent wins.
12. With `blockingStates = ILLEGAL`, a move that blocks the outer two rows of the opponent's goal while a goal cell behind them stays empty is not in the move list (the case of [Sturtevant2019, §3.3, Fig. 4(b)]). With `NONE`, the move exists. This test waits until the project defines "unreachable" for 10 pieces.
13. With `repetition = MOVE_LIMIT` and `moveLimit = 200`, a game without a winner ends after exactly 200 plies, and a win on ply 200 counts. With `REPETITION_IS_DRAW`, the first repeated position (same cells, same side to move) ends the game, and the 200-ply limit still applies.
14. With `drawHandling = SCORE_BY_DISTANCE`, the player with the smaller remaining distance gets the better score, and equal distances give equal scores. With `DRAW`, both players get the draw value.
15. With 3 players and `ranking = FIRST_WINNER_ONLY`, the game ends at the first win. The switch also accepts `FULL_RANKING`; with two players it changes nothing. With three players the rules of [Playing on for second place](#playing-on-for-second-place) hold: the first finisher leaves the turn order, its pieces stay and block, a finished goal no longer makes a move suicidal, the second finisher ends the game, the finished set is part of hash and equality, and the move limit, a repeated position or a player without a legal move rank the players still in play by distance. `engine/src/test/java/sternhalma/engine/FullRankingTest.java` checks these cases. The C++ port reproduces the golden file `engine/src/test/resources/three-player-ranking-golden.tsv` (both jump rules, won and running games, every side to move).
16. Each symmetry of [Symmetry](#symmetry) maps a legal position to a legal position. The move count of a position equals the move count of its image. The 180-degree rotation equals the player symmetry followed by the left-right flip.
17. Property test: for random legal positions, every generated move is legal, and applying then undoing a move restores the position and its hash. The engine tests use seeded random playouts for this; jqwik is not used yet.
18. With `jumpRule = LONG`, on seeded random positions, the move set contains the `ADJACENT` move set, equals the move set of an independent oracle built from the definition in [Jump rule](#jump-rule), and has no duplicate (start, end) pair. The engine reproduces the move counts and hashes of the golden file `engine/src/test/resources/long-jump-golden.tsv`.

**Proposed, not scheduled:** in the [small-board mode](#small-board-mode), the engine reproduces the "Positions" and "Illegal" columns of Table 1 for 7×7 with 1 and 2 pieces. The "Wins" and "Draws" columns are not a test target, because they need a solver.
