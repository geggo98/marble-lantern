# References

**TL;DR:** This page lists every source that the project documentation cites. Each entry has a citation key, such as **Sturtevant2019**. The other documents cite a source by its key in square brackets. A citation of [Sturtevant2019] also names the section of the paper, for example [Sturtevant2019, §3.4]. We checked the entry for [Sturtevant2019] against the PDF. Several other entries take their venue and pages from the reference list of that paper and say so. The project owner's literature survey supplied the rest; entries with incomplete data carry the mark *details not verified*.

## How we cite

The documents separate three levels of evidence. Each claim belongs to exactly one level.

| Level | Meaning | How you recognise it |
|---|---|---|
| Established | A cited source supports the claim. | The sentence carries a key. A citation of [Sturtevant2019] carries a section number, for example [Sturtevant2019, §3.2]. |
| Reported | The project owner's literature survey relays the claim. Nobody has checked it against the source. | The text says "reported" or "per the owner's survey". When we looked for the source and did not find the claim in it, the text says "reported (owner's survey, source not located)". |
| Project decision | The project chose this. No source proves that it is the best option. | The text says "project decision" or "we chose". Where the project deviates from [Sturtevant2019], one sentence says so. |

Statements that the project derives itself, without a source, are labelled "project reasoning".

Rules for authors:

1. Use only the keys on this page.
2. Cite a source only for a claim that the source supports. For [Sturtevant2019], find the claim in the paper text and give its section.
3. Do not invent page numbers, DOIs, quotes or numbers. Use only what the source or the survey gives. If you state a computed number, compute it with a command first.
4. Mark a claim as reported when you cannot check the source yourself.
5. For a source that we know only from the reference list of [Sturtevant2019], cite the key and, where it matters, add "as cited in [Sturtevant2019, §n]", where §n is the section in which the paper cites that source. The Status column below names that section.

### Sections of [Sturtevant2019]

| Section | Topic |
|---|---|
| §1 | Introduction: Halma and Chinese Checkers, motivation |
| §2 | Background and related work; §2.1 terminology |
| §3 | Rules of Chinese Checkers: board and start position |
| §3.1 | Movement rules |
| §3.2 | Winning conditions (Definition 1) |
| §3.3 | Illegal states (Definitions 2 and 3) |
| §3.4 | Draws (Definition 4) |
| §3.5 | Symmetry |
| §4 | Strongly solving Chinese Checkers: solver design |
| §5 | Results (Table 1) |
| §6 | Conclusions and future work |

## Sources

| Key | Bibliographic data | Link | Status |
|---|---|---|---|
| **Sturtevant2019** | N. R. Sturtevant, "On Strongly Solving Chinese Checkers", University of Alberta, 12 pages. Venue: Advances in Computer Games (ACG) 2019, per the owner's survey. | <https://sites.ualberta.ca/~nathanst/papers/sturtevant2019chinesecheckers.pdf> | details verified against the PDF (title, author, affiliation, sections, reference list). The PDF has no venue line, so the venue is reported. The project uses it only for verification and as an optional later test oracle. For the Table 1 caveat, see [SturtevantPubs]. |
| **Sturtevant2023** | N. R. Sturtevant, "An Efficient Chinese Checkers Implementation: Ranking, Bitboards, and BMI2 pext and pdep Instructions". Computers and Games. Springer chapter. | <https://link.springer.com/chapter/10.1007/978-3-031-34017-8_3> | title and venue as listed on [SturtevantPubs]; *year not verified* (the year comes from the key only) |
| **SturtevantPubs** | N. Sturtevant, publications page, Moving AI Lab. | <https://movingai.com/pubs.html> | page read on 2026-11-07. It carries the Table 1 caveat for [Sturtevant2019]: the 6x6 results are verified with a different solver, and the 4x4 row with 6 pieces "appears to contain a copy/paste error". Do not use that row as a test oracle; take the other rows as printed in the paper. Check the page again for the promised updated table. |
| **SturtevantSaffidine2017** | N. R. Sturtevant, A. Saffidine, "A study of forward versus backwards endgame solvers with results in Chinese Checkers". In: CGW@IJCAI, pp. 121–136 (2017). | <http://www.cs.ualberta.ca/nathanst/papers/sturtevant2017ccsolve.pdf> | venue and pages as listed in [Sturtevant2019]; link from the owner's survey, not verified. The paper cites it for its earlier work on solving parameters [Sturtevant2019, §4]. |
| **RoschkeSturtevant2013** | M. Roschke, N. Sturtevant, "UCT enhancements in Chinese Checkers using an endgame database". IJCAI Workshop on Computer Games (2013). | <http://www.cs.du.edu/~sturtevant/papers/UCT-endgame.pdf> | venue and link as listed in [Sturtevant2019]. The paper lists the first author as "M. Roschke"; [SturtevantPubs] gives "Max Roschke". DOI 10.1007/978-3-319-05428-5_5: reported (owner's survey), not verified. The paper cites it for MCTS in Chinese Checkers [Sturtevant2019, §2]. |
| **Sturtevant2002** | N. R. Sturtevant, "A comparison of algorithms for multi-player games". In: Computers and Games, pp. 108–122 (2002). | none given | as listed in [Sturtevant2019]. The paper cites it for the single-agent distance heuristic [Sturtevant2019, §2] and for the 7x7 board with 6 pieces [Sturtevant2019, §3]. |
| **Sturtevant2003** | N. Sturtevant, "Multi-player games: Algorithms and approaches", PhD thesis, UCLA (2003). Covers Max^n, Paranoid and pruning. | <http://www.cs.du.edu/~sturtevant/papers/multiplayergamesthesis.pdf> | venue and link as listed in [Sturtevant2019]; the topic summary is from the owner's survey. The paper cites it as earlier multi-player work on Chinese Checkers [Sturtevant2019, §2]. |
| **Sturtevant2015** | N. Sturtevant, "Challenges and progress on using large lossy endgame databases in Chinese Checkers". IJCAI Workshop on Computer Games (2015). | none given | as listed in [Sturtevant2019]. The paper cites it for full-board single-agent distances used in play and for TD learning [Sturtevant2019, §2]. |
| **SturtevantRutherford2013** | N. Sturtevant, M. Rutherford, "Minimizing writes in parallel external memory search". International Joint Conference on Artificial Intelligence (IJCAI), pp. 666–673 (2013). | <http://www.cs.du.edu/~sturtevant/papers/bfs_min_write.pdf> | as listed in [Sturtevant2019]. The paper cites it for single-agent distances on the full board [Sturtevant2019, §2] and for left-right symmetry [Sturtevant2019, §3.5]. |
| **Allis1994** | L. V. Allis, "Searching for Solutions in Games and Artificial Intelligence", PhD thesis, Maastricht University (1994). | none given | as listed in [Sturtevant2019]. Cite only as relayed by [Sturtevant2019, §2] (definitions of ultra-weakly, weakly and strongly solved). |
| **Bell2009** | G. I. Bell, "The Shortest Game of Chinese Checkers and Related Problems". | <https://arxiv.org/abs/0803.1245> | *details not verified* (year and venue missing; the year comes from the key only) |
| **SchaddWinands2011** | M. P. Schadd, M. H. Winands, "Best reply search for multiplayer games". IEEE Transactions on Computational Intelligence and AI in Games 3(1), pp. 57–66 (2011). | none given | volume, issue and pages as listed in [Sturtevant2019]. The paper cites it as multi-player work that uses Chinese Checkers [Sturtevant2019, §2]. |
| **NijssenWinands2010** | J. Nijssen, M. Winands, "Enhancements for Multi-Player Monte-Carlo Tree Search", Computers and Games (CG) 2010. | none given | *details not verified* (owner's survey only) |
| **Nijssen2013** | J. Nijssen, "Monte-Carlo Tree Search for Multi-Player Games", PhD thesis, Maastricht University, 2013. | none given | *details not verified* (owner's survey only) |
| **Liu2019** | Liu et al., "Towards Understanding Chinese Checkers with Heuristics, MCTS, and Deep RL", 2019. Covers AlphaZero-style approaches to Chinese Checkers. | none given | *details not verified* (full author list and venue missing) |
| **MITSlides2010** | "Artificial Intelligence and Games", MIT SP.268 lecture slides, Spring 2010 (PDF of a PowerPoint deck). | <https://web.mit.edu/Sp.268/www/2010/Artificial%20Intelligence%20and%20Games.pdf> | title and term checked against the slide text. Use it only for background: board, basic moves, history of the name, and the evaluation-function discussion. It contains no rule variants and no test values. |
| **WikipediaSternhalma** | "Sternhalma", German Wikipedia. | <https://de.wikipedia.org/wiki/Sternhalma> | page read on 2026-11-20; the quotes in [rules](rules.md#board-size-triangle-rows) were checked against it on that day. A wiki page changes; cite it only for the board, the piece counts per player count and the variant Connection. |
| **RedBlobHex** | Red Blob Games, "Hexagonal Grids". | <https://www.redblobgames.com/grids/hexagons/> | |
| **CPW** | Chessprogramming wiki. Used for alpha-beta, transposition tables and Zobrist hashing. | <https://www.chessprogramming.org/> | |
| **AlbertaThesis** | A University of Alberta master's thesis (title not verified). It trained an AlphaZero agent on a 4x4 board with 3 pieces and on a 5x5 board with 6 pieces. | none given | *details not verified* (title, author and year unknown) |

The old key **Levenspiel1971** is withdrawn. No document cited it directly. Levenspiel's 27-move solitaire solution appears in the text only through [Bell2009].

## Where each source is used

This mapping lists the documents in which each key appears in square brackets, as `[Key]` or inside a group such as `[Key, §3]`. It comes from a search of `README.md` and `docs/*.md` except this page (2026-11-07, five files).

| Key | Used in |
|---|---|
| **Sturtevant2019** | [Project overview](../README.md), [Rules](rules.md), [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md), [Endgame and opening](endgame-and-opening.md) |
| **Sturtevant2023** | [Architecture](architecture.md), [Endgame and opening](endgame-and-opening.md) |
| **SturtevantPubs** | [Rules](rules.md), [Architecture](architecture.md), [Endgame and opening](endgame-and-opening.md) |
| **SturtevantSaffidine2017** | [Endgame and opening](endgame-and-opening.md) |
| **RoschkeSturtevant2013** | [Endgame and opening](endgame-and-opening.md) |
| **Sturtevant2002** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md) |
| **Sturtevant2003** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md) |
| **Sturtevant2015** | [Search and evaluation](search-and-evaluation.md), [Endgame and opening](endgame-and-opening.md) |
| **SturtevantRutherford2013** | [Search and evaluation](search-and-evaluation.md) |
| **Allis1994** | not yet cited |
| **Bell2009** | [Rules](rules.md), [Endgame and opening](endgame-and-opening.md) |
| **SchaddWinands2011** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md) |
| **NijssenWinands2010** | [Search and evaluation](search-and-evaluation.md) |
| **Nijssen2013** | [Search and evaluation](search-and-evaluation.md) |
| **Liu2019** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md) |
| **MITSlides2010** | [Rules](rules.md), [Search and evaluation](search-and-evaluation.md) |
| **WikipediaSternhalma** | [Rules](rules.md) |
| **RedBlobHex** | [Rules](rules.md), [Architecture](architecture.md) |
| **CPW** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md), [Endgame and opening](endgame-and-opening.md) |
| **AlbertaThesis** | [Search and evaluation](search-and-evaluation.md) |

See the [project overview](../README.md) for the full document list.
