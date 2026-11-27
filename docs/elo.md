# Elo study: ratings of the engines

**TL;DR:** The project's engines now have Elo ratings, one scale per game type, with the classical browser engine at app level 3 (20,000 nodes per move) set to 1000 in each scale. On 5 rows, two players, the shipped 5-row expert (SMALL generation 9) at 200 simulations is rated 1840 ± 130 and the LARGE generation 10 network 1910 ± 140. Head to head, LARGE beats SMALL by 131 ± 24 Elo at equal simulations and loses by 173 ± 36 at equal CPU time. The intervals are three times wider than those of a plain Bradley-Terry fit, because the matches disagree with one strength number per engine by about 70 Elo per match. Two-player and three-player ratings cannot be merged into one number: the same engine setting differs by 330 to 540 Elo between the two game types. No human rating can be given; only an engine-equivalent table and the rated app levels. The study ran 11 h 18 min and used 0.44 kWh. A planned second run of 41 h is not recommended.

The published numbers are in [`models/ratings/ratings.json`](../models/ratings/ratings.json). The records of the matches and the commands to recompute every number are in [`models/runs/elo-study/`](../models/runs/elo-study/README.md).

## Contents

1. [Terms](#terms)
2. [What was run](#what-was-run)
3. [Method](#method)
4. [Results per scale](#results-per-scale)
5. [LARGE against SMALL](#large-against-small)
6. [Two against three players: no mixed Elo](#two-against-three-players-no-mixed-elo)
7. [Humans](#humans)
8. [Where the data contradict the plan](#where-the-data-contradict-the-plan)
9. [Limits](#limits)
10. [Tier 2: not recommended](#tier-2-not-recommended)
11. [Files and how to rerun](#files-and-how-to-rerun)

## Terms

Generation, Gen0, loop, SMALL, LARGE, shared network, expert, ladder, rung, opening pair, simulations and `ab:N` are defined in [models, terms](models.md#terms).

- **Elo** in this document is a position on one of the project's own rating scales. It has no relation to chess or to any federation rating.
- **Game type, scale:** a combination of player count, jump rule and board rows, rated on its own. `2p-adj-r5` = two players, neighbour jump, 5 rows. `2p-adj-r4` = the same on 4 rows. `3p-adj-r4` = three players, neighbour jump, 4 rows.
- **Classical engine:** the alpha-beta player of the browser for that game type (in `search/src/main/java/sternhalma/search/Strategies.java`: `BROWSER_SPEC` for 4 rows, `BROWSER_ROWS5_SPEC` for 5 rows, `BROWSER_3P_SPEC` for three players, the three-player alpha-beta `ab3`, which is paranoid: it assumes that both opponents play against it), in this study with the transposition-table option `tt=16`.
- **App levels 1 to 5:** the classical engine at 1,000, 5,000, 20,000, 100,000 and 500,000 nodes per move, the levels of the web UI.
- **Anchor:** app level 3 (20,000 nodes) of each scale, set to 1000.
- **Config:** what gets a rating: engine, network and budget. Example: `net:r5-small-gen9@s200` is the SMALL 5-row network of generation 9 at 200 simulations; `ab:browser-r5@n20000` is the classical 5-row engine at 20,000 nodes.
- **S0, S9, L0, L10:** the SMALL 5-row network of generation 0 and of generation 9 (the shipped 5-row expert), and the LARGE 5-row network of generation 0 and of generation 10 ([training, five-row expert net](training.md#five-row-expert-net) and [five-row LARGE net](training.md#five-row-large-net)).
- **Equal simulations, equal time:** "equal simulations" compares networks at 200 simulations each. "Equal time" compares SMALL at 1,000 simulations with LARGE at 200, which cost about the same CPU time per move in the Java player (546 and 597 CPU milliseconds per move in this study).
- **Edge:** one match between two configs. On 5 rows an edge of this study has 200 opening pairs (400 games); on 4 rows 150 pairs; with three players 150 openings, each played with the candidate in all three seats.
- **One against two:** the three-player match form of this study: one candidate against two copies of one opponent.
- **Tier 0, Tier 1, Tier 2:** Tier 0 = 19 matches on 5 rows played before the study (E1, E2, E3, H2H, equal-time and the ladder check of the two 5-row runs, described in the training sections linked above). Tier 1 = the match run of this study. Tier 2 = a larger follow-up run that was planned and not run.
- **Head-to-head difference:** the Elo difference from one match alone, 400·log10(p/(1−p)) for the score p.
- **Pooled difference:** the difference of two ratings from the fit over all matches of a scale.
- **±** is always the half-width of a 95 % interval.

## What was run

Tier 1 played 82 new edges on 2026-11-26 and 2026-11-27, with the Java match tools on one machine (`MctsMatch` for two players, `ThreeSeatMatch` for three; [training, evaluating variants](training.md#evaluating-variants)). The networks ran with `batch=1` and F32 weights; the browser's batching and F16 weights were not measured.

| Scale | Edges | Games | Configs rated |
|---|---|---|---|
| 2p-adj-r5 | 34 new + 19 of Tier 0 = 53 | 22,200 | 25: S0 to S9 at 200 simulations (generations 0, 1, 2, 4, 6, 9), S9 at 50, 100 and 1,000, S0 at 1,000, L0 and L10 at 200, nine classical budgets from 1,000 to 500,000 nodes, four rungs of the earlier ladder |
| 2p-adj-r4 | 16 | 4,800 | 10: the shared network at 50, 200 and 800 simulations, seven classical budgets from 1,000 to 100,000 nodes |
| 3p-adj-r4 | 32 one-against-two matches | 14,400 | 10: the same as 2p-adj-r4, with the three-player classical engine |

The 5-row scale exists for the energy figure of the Info part (Fig. 4.4, "what the training cost"), whose rated rows are all 5-row two-player networks. The two 4-row scales are a pilot for the question whether two- and three-player ratings can share one scale.

**Duration and energy.** The run took 11 h 18 min of wall time (plan: about 12 h, cap 16 h). The wall energy of the whole machine was 0.44 kWh (0.4397 kWh over the three match phases, 0.3329 kWh above the idle baseline of 9.59 W; plan: about 0.5 kWh). Per scale: 2p-adj-r4 0.030 kWh in 41 min, 2p-adj-r5 0.290 kWh in 7 h 28 min, 3p-adj-r4 0.120 kWh in 2 h 59 min ([energy report](../models/runs/elo-study/energy-elo-tier1.report.md)).

## Method

### The rating model

Each config has one rating per scale. For two players the model is Bradley-Terry: the expected score of x against y is 1 / (1 + 10^((R(y) − R(x)) / 400)), the usual Elo curve. For three players it is the Luce first-choice model: the probability that x wins at a table is proportional to 10^(R(x)/400); with one candidate against two copies of an opponent, R(candidate) − R(opponent) = 400·log10(2p/(1−p)) for the candidate's win share p, so p = 1/3 means equal strength. The anchor fixes the constant of each scale.

**Why level 3 is the anchor.** Origin: the owner asked on 2026-11-26 for ratings of the form "1000 ± 50" and then decided that level 3 = 1000 in every game type. Rationale: level 3 is the app's default level, exists in every game type, plays deterministically at a node budget, and lies in the middle of the classical ladder, so the intervals against it stay short. Limit: "1000" means "level 3 of this game type" and nothing more. The 5-row classical engine was never tuned, so 1000 on 5 rows anchors to a weaker engine than 1000 on 4 rows.

### Why the Bradley-Terry intervals were rejected

The first fit (`scripts/elo/fit.py`) is Bradley-Terry by maximum likelihood with a cluster bootstrap over openings. Its intervals were ±28 to ±60 Elo on 5 rows. They contain sampling error only, and the model does not fit:

- The deviance is 132.1 on 18 degrees of freedom on 5 rows, 27.5 on 7 on 4 rows and 159.8 on 85 for three players. The bootstrap p-values are 0.005, 0.010 and 0.005; 0.005 is the smallest value 200 replicates can show, so the true p is smaller.
- At the edge level, the weighted residual sum Q is 236.8 on 29 degrees of freedom on 5 rows with all data (Q/df = 8.2).
- Left out one at a time and predicted from the rest, only 20 of 49 edges on 5 rows fall inside the 95 % prediction interval of the fixed model.

The pattern behind the misfit: **Elo differences do not add up.** Matches inside a family, such as one network against its next generation or one engine against itself with more budget, show larger differences than the pooled scale can carry. The five generation steps from S0 to S9 sum to 912 Elo; the direct match S9 against S0 gives 822. L10 against L0 is 562 directly and about 868 through the SMALL networks. Strong networks also lose a few percent of their games against much weaker classical engines: S9 scored 0.971 against the classical engine at 25,600 nodes, where its rating predicts 0.985. A logistic curve with one scale does not describe this. The data do not show which mechanism causes it, and this document claims none.

### The published fit: random effects

The published ratings come from a random-effects fit at the edge level, method tag `re-nma-pm-kh` (`scripts/elo/fit_re.py`):

1. Per edge, the observed difference is y = 400·log10(p/(1−p)) with p = (points + 0.5) / (games + 1); one against two: y = 400·log10(2p/(1−p)) with a prior of 1/3 instead of 0.5.
2. The standard error of y comes from the variance of the score over opening pairs or openings (binomial for the 6 edges of Tier 0 that have totals only).
3. Weighted least squares over all edges of a scale gives one rating per config, with weights 1 / (se² + tau²).
4. tau is the standard deviation of the true difference of an edge around the difference of the two ratings: the disagreement between matches. It is estimated by the Paule-Mandel rule (Q equals its degrees of freedom).
5. The half-width is the Student t quantile with the degrees of freedom of the scale times the standard error against the anchor (Knapp-Hartung).

tau is 72 Elo on 5 rows (95 % interval 54 to 102), 38 on 2p-adj-r4 (20 to 86) and 48 on 3p-adj-r4 (31 to 78). The median standard error of one edge is about 20 Elo, so the disagreement between matches is about 3.5 times the sampling error of a match.

**Check.** Each edge whose removal leaves every config connected to the anchor was left out and predicted from the rest:

| Scale | Covered by the random-effects interval | Covered by the fixed-model interval |
|---|---|---|
| 2p-adj-r5 | 47 of 49 (96 %) | 20 of 49 (41 %) |
| 2p-adj-r4 | 16 of 16 (100 %) | 10 of 16 (63 %) |
| 3p-adj-r4 | 31 of 32 (97 %) | 22 of 32 (69 %) |

The random-effects intervals have the coverage they claim; the Bradley-Terry intervals do not. A delete-one-edge jackknife gave ±88 to ±150 Elo for the 5-row configs with many edges, the same size as the published half-widths.

**Sensitivity.** Whether the saturated direct matches of Tier 0 (network against classical, scores above 0.9) enter the fit moves the top networks by 100 to 190 Elo, the size of their intervals. The published values include them, because the study design intended every Tier 0 edge to enter. They are the lowest of the variants examined: S9 at 200 simulations is 1839 with all data, 1968 in the Bradley-Terry fit of Tier 1, 1966 without saturated edges.

## Results per scale

All values from [`models/ratings/ratings.json`](../models/ratings/ratings.json), method `re-nma-pm-kh`, anchor level 3 = 1000. The Bradley-Terry value of Tier 1 is shown for comparison where it exists.

### 2p-adj-r5: two players, 5 rows

The rows of the energy figure, with the recommended display (rounded to 10, half-width rounded up):

| Energy row, basis | Config | Elo ± | Display |
|---|---|---|---|
| SMALL generation 0, equal simulations | `net:r5-small-gen0@s200` | 1197 ± 100 | 1200 ± 100 |
| SMALL generation 9, equal simulations | `net:r5-small-gen9@s200` | 1839 ± 125 | 1840 ± 130 |
| LARGE generation 0, both bases | `net:r5-large-gen0@s200` | 1321 ± 116 | 1320 ± 120 |
| LARGE generation 10, both bases | `net:r5-large-gen10@s200` | 1907 ± 132 | 1910 ± 140 |
| SMALL generation 9, equal time | `net:r5-small-gen9@s1000` | 2151 ± 164 | 2150 ± 170 |

All rated configs of the scale:

| Config | Elo ± | Bradley-Terry, Tier 1 |
|---|---|---|
| S9 at 1,000 simulations | 2151 ± 164 | 2297 ± 60 |
| L10 at 200 | 1907 ± 132 | 2092 ± 52 |
| S9 at 200 (shipped 5-row expert) | 1839 ± 125 | 1968 ± 47 |
| SMALL generation 6 at 200 | 1799 ± 138 | 1881 ± 45 |
| SMALL generation 4 at 200 | 1705 ± 137 | 1778 ± 44 |
| S9 at 100 | 1705 ± 159 | 1804 ± 49 |
| SMALL generation 2 at 200 | 1598 ± 114 | 1636 ± 38 |
| S9 at 50 | 1570 ± 164 | 1653 ± 45 |
| S0 at 1,000 | 1560 ± 150 | 1551 ± 41 |
| Classical 500,000 nodes (level 5) | 1455 ± 154 | 1533 ± 46 |
| Classical 223,607 | 1362 ± 146 | 1430 ± 42 |
| SMALL generation 1 at 200 | 1360 ± 107 | 1379 ± 34 |
| L0 at 200 | 1321 ± 116 | 1310 ± 34 |
| Classical 102,400 (`ab:102400` of the earlier ladder) | 1307 ± 173 | Tier 0 only |
| Classical 100,000 (level 4) | 1259 ± 126 | 1277 ± 33 |
| S0 at 200 | 1197 ± 100 | 1204 ± 28 |
| Classical 44,721 | 1153 ± 108 | 1151 ± 26 |
| Classical 25,600 (`ab:25600` of the earlier ladder) | 1117 ± 134 | Tier 0 only |
| Classical 6,400 (`ab:6400` of the earlier ladder) | 1030 ± 130 | Tier 0 only |
| Classical 20,000 (level 3, anchor) | 1000 | 1000 |
| Classical 10,000 | 922 ± 152 | 922 ± 34 |
| Classical 5,000 (level 2) | 826 ± 214 | 826 ± 48 |
| Classical 1,600 (`ab:1600` of the earlier ladder) | 818 ± 134 | Tier 0 only |
| Classical 2,236 | 661 ± 263 | 661 ± 54 |
| Classical 1,000 (level 1) | 484 ± 303 | 484 ± 65 |

The rungs of the earlier ladder (1,600, 6,400, 25,600 and 102,400 nodes) were played only by networks, in Tier 0, without `tt=16`. Their places come from those network matches. The lower classical rungs hang on a chain with one edge per step, so tau accumulates along the chain; that is why level 1 has ±303. S0 at 1,000 simulations and S9 at 1,000 rest on 2 edges each and are the weakest part of the scale.

**Overlapping bars do not decide the order.** All ratings of a scale share the uncertain path to the anchor. Whether A is stronger than B is decided by the head-to-head match where one exists, otherwise by the pooled pairwise interval in `ratings.json` (`scales.2p-adj-r5.pairs`); a pair whose interval contains 0 is unresolved. This rule replaces the plan's rule "use the pooled interval for every pair", because with honest intervals the pooled difference of L10 and S9 at equal simulations (+68 ± 85) would turn a clearly resolved match into "unresolved".

**Shift against the old figures.** The energy figure so far showed head-to-head differences against `ab:25600`: S0 +131 ± 33, S9 +611 ± 112, L0 +242 ± 39, L10 +798 ± 261 ([training, strength per cost](training.md#strength-per-cost)). On the new scale `ab:25600` sits at 1117, so the published values correspond to S0 +80, S9 +722, L0 +204, L10 +790 against it. Relative to S0, the published values give S9 +642 ± 87, L10 +710 ± 97 and L0 +124 ± 76; the old figures gave +480, +667 and +111. Three causes: the anchor changed from 25,600 to 20,000 nodes; one saturated match carries the old S9 figure (at 0.971, eight losses more or fewer in 400 games move the result by about −100 or +190 Elo); and Tier 1 reaches S9 through five generation steps, which sum to more than the direct match.

### 2p-adj-r4: two players, 4 rows

| Config | Elo ± |
|---|---|
| Classical 100,000 (level 4) | 1227 ± 94 |
| Shared network at 800 simulations | 1188 ± 75 |
| Classical 44,721 | 1098 ± 74 |
| Classical 20,000 (level 3, anchor) | 1000 |
| Shared network at 200 | 901 ± 65 |
| Classical 10,000 | 863 ± 80 |
| Classical 5,000 (level 2) | 838 ± 86 |
| Classical 2,236 | 705 ± 116 |
| Classical 1,000 (level 1) | 604 ± 116 |
| Shared network at 50 | 569 ± 89 |

Level 5 (500,000 nodes) was not rated on 4 rows. The 4-row expert and the other 4-row networks were not played (they were part of the Tier 2 design).

### 3p-adj-r4: three players, 4 rows

| Config | Elo ± |
|---|---|
| Shared network at 800 simulations | 1521 ± 60 |
| Shared network at 200 | 1332 ± 50 |
| Classical 100,000 (level 4) | 1238 ± 71 |
| Classical 44,721 | 1202 ± 56 |
| Shared network at 50 | 1108 ± 66 |
| Classical 20,000 (level 3, anchor) | 1000 |
| Classical 10,000 | 835 ± 60 |
| Classical 5,000 (level 2) | 784 ± 69 |
| Classical 2,236 | 750 ± 88 |
| Classical 1,000 (level 1) | 742 ± 89 |

This scale is a Luce rating from one-against-two tables: it states the odds that a player wins a table against two copies of another player, with every opening played in all three seats. It is not a strength against a mixed field. Publish its numbers only with three qualifiers: from one-against-two tables; kingmaking (a losing player deciding which opponent wins) cannot be removed; the roles are asymmetric. The last one means: the difference between two players is lower when the weaker one sits alone against two copies of the stronger one than in the reverse table, in 13 of 16 pairs (81 %), combined z = −4.5. Tables with three different players were not played, so this is untested in mixed games. The first seat carries a 20 to 26 % higher Luce weight than the later seats; the design balances this because every opening is played in all seats. The classical three-player engine gains almost nothing from 1,000 to 5,000 nodes.

## LARGE against SMALL

Head-to-head matches from the 5-row runs ([training, strength per cost](training.md#strength-per-cost)); the same games are edges of the fit:

- **Equal simulations** (200 each): L10 scores 0.680 over 800 games against S9: **+131 ± 24 Elo**.
- **Equal CPU time** (S9 at 1,000 simulations, L10 at 200): L10 scores 0.270 over 400 games: **−173 ± 36 Elo**.

Publish these head-to-head numbers, labelled as such, and not the difference of two ratings. The pooled differences (+68 ± 85 and −244 ± 117) agree in sign and contain the head-to-head values, but the pooled scale cannot resolve the equal-simulation gap. Other paths in the data put the equal-time gap between 173 and about 260 Elo: S9 at 1,000 beats S9 at 200 by 390 ± 55 directly, but by 303 through L10. Tier 1 played no new match with L10; it has its 3 network edges and 4 saturated classical edges of Tier 0 only.

Independent Tier 1 evidence for generation 0: L0 against S0 at 200 simulations scored 0.620 over 400 games, +85 ± 33. LARGE is stronger at equal simulations in both generations.

Equal time holds for the Java player on the training machine's CPU: 546.1 CPU milliseconds per move for S9 at 1,000 simulations against 596.8 for L10 at 200. It does not hold for the browser or a GPU.

## Two against three players: no mixed Elo

**Verdict: separate scales, no mixed Elo, no conversion map.** Origin: the study design fixed a test before the run, and the owner decided to publish separate scales with a labelled bridge table and no mixed Elo number. The test rejected one common scale clearly.

A bridge player is a config rated in both 2p-adj-r4 and 3p-adj-r4: the classical engine at four budgets and the shared network at three simulation counts. Level 3 = 1000 in both columns; beyond that, the numbers of the two columns cannot be compared.

| Bridge player | 2p-adj-r4 | 3p-adj-r4 | 2p minus 3p |
|---|---|---|---|
| Classical 1,000 nodes (level 1) | 604 ± 116 | 742 ± 89 | −138 |
| Classical 5,000 (level 2) | 838 ± 86 | 784 ± 69 | +54 |
| Classical 20,000 (level 3) | 1000 | 1000 | 0 by definition |
| Classical 100,000 (level 4) | 1227 ± 94 | 1238 ± 71 | −11 |
| Shared network, 50 simulations | 569 ± 89 | 1108 ± 66 | −539 |
| Shared network, 200 simulations | 901 ± 65 | 1332 ± 50 | −431 |
| Shared network, 800 simulations | 1188 ± 75 | 1521 ± 60 | −334 |

The test of the study design asked whether one offset per scale explains the bridge players. With the Bradley-Terry covariance it gives chi² = 1616 on 6 degrees of freedom and a remaining spread of 234 Elo (the design allowed 50). With the wider random-effects intervals, each network difference still exceeds its half-width by a factor of 3.5 to 5.3. The verdict does not depend on the interval method.

**Why no map from one scale to the other can exist.** Two players with almost the same two-player rating differ by about 500 in the three-player game: the shared network at 200 simulations (901) and the classical engine between 5,000 and 10,000 nodes (838 to 863) score 1332 and about 784 to 835 with three players. A map per engine family would need the engine as input, and even then the offset is not constant: −539 to −334 for the network, −138 to +54 for the classical engine. Relative to the paranoid three-player alpha-beta engine, the network is far stronger than relative to the two-player engine; the "same" classical engine is only nominally the same in both games. The regression slope of 1.34 that the bridge test reports comes from the two groups and is not a unit conversion: inside each family the three-player scale is compressed (ratio 0.80 for the classical engine, 0.67 for the network).

Do not publish a "mean over game types" either: any single number would hide a disagreement of 330 to 540 Elo.

## Humans

**No defensible human Elo exists.** The repository holds no games of humans against the engines, and the app has no named human classes: the skill card ([web UI, skill estimate](web-ui.md#skill-estimate)) reports only a node budget and a position relative to levels 1 to 5. A table such as "beginner = x, club player = y" would be invented. Origin of this scope: the owner decided on 2026-11-26 that humans get an estimate only.

**What can be said without a model:** a person who wins about half of the games against level k has about the rating of level k. The rated levels:

| Level | Nodes | 2p-adj-r5 | 2p-adj-r4 | 3p-adj-r4 |
|---|---|---|---|---|
| 1 | 1,000 | 484 ± 303 | 604 ± 116 | 742 ± 89 |
| 2 | 5,000 | 826 ± 214 | 838 ± 86 | 784 ± 69 |
| 3 | 20,000 | 1000 | 1000 | 1000 |
| 4 | 100,000 | 1259 ± 126 | 1227 ± 94 | 1238 ± 71 |
| 5 | 500,000 | 1455 ± 154 | not rated | not rated |

**Engine-equivalent table, 4 rows, two players.** The skill estimate maps a person's mean loss per move (capped at 200 points, judged by a 20,000-node analysis) to the node budget of the classical engine with the same mean loss. It is calibrated only on 4 rows with two players. Reading the rating of that budget from the 2p-adj-r4 scale gives a "move-quality equivalent", not a measured rating:

| Skill card output | Engine-equivalent Elo |
|---|---|
| Weaker than anything measured, or below level 1 | below 604; no rung below 1,000 nodes was rated |
| At level 1 | 604 (rung ± 116) |
| Between level 1 and 2 | 604 to 838 |
| At level 2 | 838 (rung ± 86) |
| Between level 2 and 3 | 838 to 1000 |
| At level 3 | 1000 |
| Between level 3 and 4, below 50,000 nodes | 1000 to about 1116 |
| At least 50,000 nodes (the top of the estimator) | at least about 1116; the estimator cannot tell level 4 (1227 ± 94) from level 5 |

The part of the error that can be bounded combines the sampling of the person's moves, the calibration of the estimator and the rating of the rung: about ±150 Elo with 1,000 measured moves (the most the app stores per game type, about ten games), ±210 with 300 moves and ±320 with 100 moves. The card's own 80 % interval covers only the first part. Four sources of error cannot be bounded with the data in the repository:

1. The assumption "same mean move loss, same playing strength" for a human against an engine.
2. The judge is a 20,000-node search with the same evaluation as the rated engine and shares its blind spots.
3. The losses of moves within one game are correlated; the calibration treats them as independent.
4. No rung below 1,000 nodes is rated, and above 50,000 nodes the estimator is flat.

On 5 rows the estimator runs but is not calibrated, so only the level table applies. A measured human rating needs games against rated levels: about 46 well-matched games for ±100 Elo. The planned file export of the skill data is described in [web UI, planned: human data export](web-ui.md#planned-human-data-export).

## Where the data contradict the plan

The study followed a written plan of 2026-11-26, which is not in the repository; this section states each of its assumptions in place. Seven of them did not hold:

1. **Intervals.** The plan expected the cluster bootstrap to give usable intervals of about ±35 Elo pairwise. The sampling error is as planned (±28 to ±60 against the anchor), but the model error is three times larger; honest intervals are ±100 to ±165 for the 5-row networks.
2. **One number per config.** The plan assumed Bradley-Terry fits. The data reject it in all three scales (p ≤ 0.01).
3. **Chains of small steps.** The plan built each scale from chains of adjacent, unsaturated steps to avoid saturated edges. The chains accumulate: sums of steps exceed direct long matches. When additivity fails, a sparse chain is the wrong design; direct matches across families at several distances are needed.
4. **Compare rule.** The plan wanted the pooled pairwise interval for every pair. It is replaced by the head-to-head match where one exists ([results, 2p-adj-r5](#2p-adj-r5-two-players-5-rows)).
5. **Correlation within an opening pair.** The plan assumed a positive correlation, which would make independent resampling too narrow. Measured: the two games of a pair are negatively correlated (variance ratio cluster/binomial 0.85 on 5 rows, range 0.54 to 1.02), because an opening that favours one side gives each player one win. The goodness-of-fit test of `fit.py` is therefore conservative, and the one edge of the Tier 1 Bradley-Terry fit that has totals only (the equal-time match) has an interval slightly too wide, not too narrow as the fit report notes.
6. **Three-player role test.** The plan's rule for "one-dimensional" (a single role swap with |z| > 3) has too little power at 150 openings; the pooled role-swap test rejects ([3p-adj-r4](#3p-adj-r4-three-players-4-rows)). The fit report's verdict "one-dimensional" is therefore not supported.
7. **Tier 0 input.** The plan said every earlier ladder match enters Tier 1. The Bradley-Terry fit of Tier 1 took only 4 of the 19 Tier 0 edges. The published fit uses all 19.

What held: the time and energy estimates (11.3 h against about 12 h, 0.44 against about 0.5 kWh); the expected "separate" bridge verdict (the plan predicted about 400 Elo disagreement, measured 330 to 540); and the equal-time CPU ratio (the largest and smallest CPU time per move of the equal-time configs differ by a factor of 1.16).

## Limits

- The ratings describe this pool of engines. All top-rated networks descend from self-play against their predecessors, and the scale does not predict scores against far opponents.
- The random-effects model assumes a normal disagreement per edge with the same tau for every kind of edge, although part of the disagreement is systematic (inside families, see [method](#why-the-bradley-terry-intervals-were-rejected)). It works on the logit scale and ignores the seat factors of three players.
- The Tier 0 classical engine ran without `tt=16`, with the engine's default table size. Its effect was not examined.
- One machine, the Java player with `batch=1` and F32 weights. The browser engines were not measured.
- 3p-adj-r4 rests on one-against-two tables only; tables with three different players were not played.
- The bridge analysis has 7 bridge players, of which 6 carry information, from 2 engine families and 1 network. It cannot show the shape of the disagreement, only its size.

## Tier 2: not recommended

The planned Tier 2 run (about 41 h) was not run. The intervals are dominated by the disagreement between matches (tau 38 to 72 Elo per edge), not by sampling (about 20 Elo per edge). Its largest block, 8.3 h of more games on the Tier 1 edges, would shrink only the sampling part and leave ±100 to ±165 almost unchanged. More independent paths help; more games per edge do not. The bridge verdict is already decisive, and 17.5 h of further three-player matches would not change it.

If ratings of the shipped 4-row models or a human table below level 1 are wanted: a reduced run of about 10 h on 2p-adj-r4 only, 150 to 200 pairs per edge, with classical rungs at 50, 200 and 500 nodes, and every network playing at least three classical rungs directly at unsaturated scores (0.25 to 0.75). For three players, the six tables with three different players come before any further one-against-two matches, because the role asymmetry is the open question. For 2p-adj-r5 a few cheap additions help: S9 at 1,000 simulations and L10 each against two classical rungs near 500,000 nodes or above, and S9 at 1,000 against one more network, so that the two equal-time configs no longer hang on 2 and 3 edges.

## Files and how to rerun

| File | What it holds |
|---|---|
| [`models/ratings/ratings.json`](../models/ratings/ratings.json) | the published numbers: per scale and config Elo, half-width, method and the Bradley-Terry value; tau; pooled and head-to-head pairs; the energy-table rows with display values; the compare rule and caveats; the bridge table and test; the level tables; the human table; the Tier 2 advice |
| [`models/ratings/tier1-bt-ratings.json`](../models/ratings/tier1-bt-ratings.json), [`tier1-bt-fit-report.md`](../models/ratings/tier1-bt-fit-report.md) | the Bradley-Terry fit of Tier 1 and its diagnostics (edges, cycles, leave-one-out, sensitivity, bridge test) |
| [`models/runs/elo-study/`](../models/runs/elo-study/README.md) | the raw match results of Tier 0 and Tier 1, the driver log, the cost data and the energy report |
| [`scripts/elo/fit_re.py`](../scripts/elo/fit_re.py) | the random-effects fit that produces the published numbers |
| [`scripts/elo/fit.py`](../scripts/elo/fit.py) | the Bradley-Terry fit with bootstrap and diagnostics |
| [`scripts/elo/run.py`](../scripts/elo/run.py), [`ingest.py`](../scripts/elo/ingest.py), [`FORMAT.md`](../scripts/elo/FORMAT.md) | the match driver, the import of earlier matches, the common edge format |
| [`scripts/elo/configs.tsv`](../scripts/elo/configs.tsv), [`design-tier1.tsv`](../scripts/elo/design-tier1.tsv), [`design-tier2.tsv`](../scripts/elo/design-tier2.tsv) | the config registry and the designs of the two tiers |

The commands that recompute both fits from the committed records are in the [records README](../models/runs/elo-study/README.md#recomputing-the-ratings). The random-effects fit takes about a second and reproduces every number of `ratings.json` within rounding; its tests are `scripts/elo/test/test_fit_re.py`:

```zsh
python3 scripts/elo/test/test_fit_re.py
python3 scripts/elo/test/test_fit.py
```

The numbers of `ratings.json` were first computed by the analysis scripts of the evaluation, outside the repository. `fit_re.py` is a port of those scripts; on the same input it gives the same numbers to within 0.5 Elo before rounding.
