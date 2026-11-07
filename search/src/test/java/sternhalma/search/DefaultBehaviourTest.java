package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Regression test: with default options the baseline searches and plays exactly as before the search
 * enhancements were added. The fingerprints were recorded with the code of commit 78eb955.
 */
class DefaultBehaviourTest {

    private static Position randomPosition(long seed, int plies) {
        Random random = new Random(seed);
        Position pos = Position.initial(RuleSet.defaults(2));
        for (int i = 0; i < plies; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        return pos;
    }

    /** Move, score, depth and node count of node-limited searches with one reused instance. */
    static long searchFingerprint() {
        AlphaBeta search = new AlphaBeta(new DistanceEvaluator(), 18, true);
        long h = 17;
        for (long seed = 1; seed <= 8; seed++) {
            Position pos = randomPosition(seed, 4 + 6 * (int) seed);
            SearchResult r = search.search(pos, 30, Long.MAX_VALUE, 20_000);
            h = h * 31 + r.move();
            h = h * 31 + r.score();
            h = h * 31 + r.depth();
            h = h * 31 + r.nodes();
        }
        return h;
    }

    /** The move sequence of a whole game between two baseline specs from a fixed opening. */
    static long gameFingerprint() {
        Position start = Arena.opening(RuleSet.defaults(2), 7, 0, 4);
        AlphaBetaPlayer a = PlayerSpec.parse("ab:eval=distance,nodes=20000").create();
        AlphaBetaPlayer b = PlayerSpec.parse("ab:eval=distance,nodes=5000").create();
        Position pos = start.copy();
        long h = 17;
        while (pos.outcome() == null && pos.legalMoves().length > 0) {
            int m = (pos.sideToMove() == 0 ? a : b).choose(pos);
            h = h * 31 + m;
            pos.make(m);
        }
        return h * 31 + a.totalNodes() * 7 + b.totalNodes();
    }

    @Test
    void defaultOptionsSearchExactlyAsBefore() {
        assertEquals(SEARCH, searchFingerprint());
    }

    @Test
    void defaultSpecsPlayExactlyAsBefore() {
        assertEquals(GAME, gameFingerprint());
    }

    private static final long SEARCH = 2075083229281839956L;
    private static final long GAME = -4819497751799443868L;
}
