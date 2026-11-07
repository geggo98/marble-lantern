# References

**TL;DR:** This page lists every source that the project documentation cites. Each entry has a citation key, such as **Sturtevant2019**. The other documents cite a source by its key in square brackets, for example [Sturtevant2019]. The project owner supplied the bibliographic data from a literature survey. Nobody has verified all of it independently. Entries with incomplete data carry the mark *details not verified*.

## How we cite

The documents separate three levels of evidence. Each claim belongs to exactly one level.

| Level | Meaning | How you recognise it |
|---|---|---|
| Established | A cited source supports the claim. | The sentence carries a key, for example "according to [Sturtevant2019]". |
| Reported | The project owner's literature survey relays the claim. Nobody has checked it against the source again. | The text says "reported" or "per the owner's survey". A key can appear if the survey names a source. |
| Project decision | The project chose this. No source proves that it is the best option. | The text says "project decision" or "we chose". |

Rules for authors:

1. Use only the keys on this page.
2. Cite a source only for a claim that the source supports.
3. Do not invent page numbers, DOIs, quotes or numbers. Use only what the survey or the source gives.
4. Mark a claim as reported when you cannot check the source yourself.

## Sources

| Key | Bibliographic data | Link | Status |
|---|---|---|---|
| **Sturtevant2019** | N. Sturtevant, "On Strongly Solving Chinese Checkers", ACG 2019. | <https://webdocs.cs.ualberta.ca/~nathanst/papers/sturtevant2019chinesecheckers.pdf> | |
| **Sturtevant2023** | N. Sturtevant, "An Efficient Chinese Checkers Implementation: Ranking, Bitboards, and BMI2 pext and pdep". Springer chapter. | <https://link.springer.com/chapter/10.1007/978-3-031-34017-8_3> | *details not verified* (book title and year missing) |
| **SturtevantSaffidine2017** | N. Sturtevant, A. Saffidine, "A study of forward versus backwards endgame solvers with results in Chinese Checkers", 2017. | <http://www.cs.ualberta.ca/nathanst/papers/sturtevant2017ccsolve.pdf> | *details not verified* (venue missing) |
| **RoschkeSturtevant2013** | C. Roschke, N. Sturtevant, "UCT enhancements in Chinese Checkers using an endgame database", Computer Games Workshop (CGW) 2013. Springer, DOI 10.1007/978-3-319-05428-5_5. | <https://doi.org/10.1007/978-3-319-05428-5_5> | |
| **Bell2009** | G. I. Bell, "The Shortest Game of Chinese Checkers and Related Problems". | <https://arxiv.org/abs/0803.1245> | *details not verified* (year and venue missing; the year comes from the key only) |
| **SchaddWinands2011** | M. Schadd, M. Winands, "Best Reply Search for Multiplayer Games", IEEE Transactions on Computational Intelligence and AI in Games 3(1), 2011. | none given | |
| **NijssenWinands2010** | J. Nijssen, M. Winands, "Enhancements for Multi-Player Monte-Carlo Tree Search", Computers and Games (CG) 2010. | none given | |
| **Nijssen2013** | J. Nijssen, "Monte-Carlo Tree Search for Multi-Player Games", PhD thesis, Maastricht University, 2013. | none given | |
| **Sturtevant2003** | N. Sturtevant, "Multi-Player Games: Algorithms and Approaches", PhD thesis, UCLA, 2003. Covers Max^n, Paranoid and pruning. | none given | |
| **Liu2019** | Liu et al., "Towards Understanding Chinese Checkers with Heuristics, MCTS, and Deep RL", 2019. Covers AlphaZero-style approaches to Chinese Checkers. | none given | *details not verified* (full author list and venue missing) |
| **RedBlobHex** | Red Blob Games, "Hexagonal Grids". | <https://www.redblobgames.com/grids/hexagons/> | |
| **CPW** | Chessprogramming wiki. Used for alpha-beta, transposition tables and Zobrist hashing. | <https://www.chessprogramming.org/> | |
| **Levenspiel1971** | Levenspiel's 27-move solitaire solution (1971), as reported in [Bell2009]. Do not cite Levenspiel's own publication directly. | none given | *details not verified* (secondary reference only) |
| **AlbertaThesis** | A University of Alberta master's thesis (title not verified). It trained an AlphaZero agent on a 4x4 board with 3 pieces and on a 5x5 board with 6 pieces. | none given | *details not verified* (title, author and year unknown) |

The link for **RoschkeSturtevant2013** is the standard resolver form of the DOI in its entry. The survey gives no other link for it.

## Where each source is used

This mapping lists the documents in which each key appears in square brackets.

| Key | Used in |
|---|---|
| **Sturtevant2019** | [Project overview](../README.md), [Rules](rules.md), [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md), [Endgame and opening](endgame-and-opening.md) |
| **Sturtevant2023** | [Architecture](architecture.md), [Endgame and opening](endgame-and-opening.md) |
| **SturtevantSaffidine2017** | [Endgame and opening](endgame-and-opening.md) |
| **RoschkeSturtevant2013** | [Endgame and opening](endgame-and-opening.md) |
| **Bell2009** | [Rules](rules.md), [Endgame and opening](endgame-and-opening.md) |
| **SchaddWinands2011** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md) |
| **NijssenWinands2010** | [Search and evaluation](search-and-evaluation.md) |
| **Nijssen2013** | [Search and evaluation](search-and-evaluation.md) |
| **Sturtevant2003** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md) |
| **Liu2019** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md) |
| **RedBlobHex** | [Rules](rules.md), [Architecture](architecture.md) |
| **CPW** | [Architecture](architecture.md), [Search and evaluation](search-and-evaluation.md), [Endgame and opening](endgame-and-opening.md) |
| **Levenspiel1971** | Not yet cited as a key. [Rules](rules.md) and [Endgame and opening](endgame-and-opening.md) name Levenspiel's 27-move solution and cite it through [Bell2009]. |
| **AlbertaThesis** | [Search and evaluation](search-and-evaluation.md) |

See the [project overview](../README.md) for the full document list.
