package sternhalma.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * The fixed position set of the round-2 yardstick (docs/search-and-evaluation.md, "Round 2 yardstick"), used by
 * {@link Fingerprint} and {@link Bench}.
 *
 * <p>{@value #GAMES} self-play games of R2 at {@value #GENERATOR_NODES} nodes per move start from
 * {@value #OPENING_PLIES} random plies (seed {@value #SEED}); from each game {@value #PER_GAME} distinct plies
 * between {@value #MIN_PLY} and {@value #MAX_PLY} are drawn, which gives {@value #COUNT} positions from the
 * opening, the middle game and the early race. Two further openings ({@link #gameOpening(int)}) start the
 * full games that the fingerprint and the bench play.
 *
 * <p>Everything is a move list from {@code Position.initial(RuleSet.defaults(2))}. That keeps the set
 * independent of the classes of one build: {@link Bench} replays the same lists in the frozen pre-round build.
 * Nothing is stored on disk; the set is generated again in about a second. It depends on the tree of R2 at
 * {@value #GENERATOR_NODES} nodes, which the fingerprint pins, so it is the same in every build that keeps
 * R2's tree.
 */
public final class ReferencePositions {

    /**
     * Reference player R2: the BEST_SPEC of round 1 with its weights frozen as {@code linear-r1}, 20000 nodes.
     * Every later build can construct it; its tree is pinned by {@code BestSpecFingerprintTest}.
     */
    public static final String R2_SPEC =
            "ab:eval=linear-r1,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,partial=1,nodes=20000";

    /**
     * Reference player R3: the BEST_SPEC of the round-2 integration at 20000 nodes, frozen because it scored above
     * 0.80 against R2 at equal CPU time (the re-base rule of the round-2 plan). The text is a literal, not a
     * reference to {@code Strategies.BEST_SPEC}, so a later integration that moves BEST_SPEC leaves R3 where it is.
     * Its evaluator {@code racelin1-r3} is built from frozen parts only ({@code LinearWeights.ROUND3}, the rows&gt;=1
     * race table with its recorded checksum), so a later refit of the live weights cannot move it either; its tree
     * is pinned by {@code BestSpecFingerprintTest}, and the jars of its build are frozen in {@code .reference-r3/}
     * (docs/search-and-evaluation.md, "Integration of round 2"). The reference positions stay those of R2.
     */
    public static final String R3_SPEC = "ab:eval=racelin1-r3,pvs=1,killers=1,history=1,lmr=2,asp=1,ttrep=1,mate=1,"
            + "partial=1,fut=1,futk=20,fut2=1,race=1,racerows=1,nodes=20000";

    /** Seed of the openings and of the ply sample. Changing it changes every recorded fingerprint. */
    public static final long SEED = 1000;
    public static final int GAMES = 8;
    public static final int PER_GAME = 8;
    public static final int COUNT = GAMES * PER_GAME;
    public static final int MIN_PLY = 8;
    public static final int MAX_PLY = 100;
    public static final int OPENING_PLIES = 4;
    public static final long GENERATOR_NODES = 1000;
    /** Number of extra openings for full games, numbered after the {@value #GAMES} generator games. */
    public static final int FULL_GAMES = 2;

    private static List<int[]> cached;

    private ReferencePositions() {
    }

    /** The rules of every reference position: the defaults for two players. */
    public static RuleSet rules() {
        return RuleSet.defaults(2);
    }

    /**
     * The {@value #COUNT} positions as move lists from the initial position, in a fixed order (game by game,
     * by ascending ply). Callers get copies.
     */
    public static synchronized List<int[]> moveLists() {
        if (cached == null) {
            cached = generate();
        }
        return cached.stream().map(int[]::clone).toList();
    }

    /** The {@value #COUNT} positions. */
    public static List<Position> positions() {
        return moveLists().stream().map(ReferencePositions::replay).toList();
    }

    /** The opening (move list) of full game {@code index}, 0 &lt;= index &lt; {@value #FULL_GAMES}. */
    public static int[] gameOpening(int index) {
        if (index < 0 || index >= FULL_GAMES) {
            throw new IllegalArgumentException("full game index must be in 0.." + (FULL_GAMES - 1));
        }
        return opening(GAMES + index);
    }

    /** Plays a move list from the initial position. */
    public static Position replay(int[] moves) {
        Position pos = Position.initial(rules());
        for (int m : moves) {
            pos.make(m);
        }
        return pos;
    }

    /** {@value #OPENING_PLIES} uniformly random legal plies; game {@code g} has its own random stream. */
    static int[] opening(int game) {
        SplittableRandom random = new SplittableRandom(SEED * 1_000_003L + game);
        Position pos = Position.initial(rules());
        int[] moves = new int[OPENING_PLIES];
        for (int i = 0; i < OPENING_PLIES; i++) {
            int[] legal = pos.legalMoves();
            moves[i] = legal[random.nextInt(legal.length)];
            pos.make(moves[i]);
        }
        return moves;
    }

    private static List<int[]> generate() {
        PlayerSpec r2 = PlayerSpec.parse(R2_SPEC);
        PlayerSpec generator = new PlayerSpec(R2_SPEC + " at " + GENERATOR_NODES + " nodes", r2.evaluator(),
                GENERATOR_NODES, r2.depth(), r2.millis(), r2.tableBits(), r2.options(), r2.weights());
        List<int[]> result = new ArrayList<>(COUNT);
        for (int g = 0; g < GAMES; g++) {
            int[] game = selfPlay(opening(g), generator);
            int last = Math.min(MAX_PLY, game.length - 1); // a position before the last move is never terminal
            int candidates = last - MIN_PLY + 1;
            if (candidates < PER_GAME) {
                throw new IllegalStateException("reference game " + g + " is too short: " + game.length + " plies");
            }
            int[] plies = new int[candidates];
            for (int i = 0; i < candidates; i++) {
                plies[i] = MIN_PLY + i;
            }
            SplittableRandom random = new SplittableRandom(SEED * 7_919L + g);
            for (int i = 0; i < PER_GAME; i++) { // partial Fisher-Yates shuffle
                int j = i + random.nextInt(candidates - i);
                int t = plies[i];
                plies[i] = plies[j];
                plies[j] = t;
            }
            int[] chosen = Arrays.copyOf(plies, PER_GAME);
            Arrays.sort(chosen);
            for (int ply : chosen) {
                result.add(Arrays.copyOf(game, ply));
            }
        }
        return List.copyOf(result);
    }

    /** The complete move list of a self-play game of {@code spec} (fresh players) after {@code opening}. */
    static int[] selfPlay(int[] opening, PlayerSpec spec) {
        Position pos = replay(opening);
        AlphaBetaPlayer[] players = {spec.create(), spec.create()};
        int[] moves = Arrays.copyOf(opening, Position.MAX_MOVES);
        int n = opening.length;
        while (pos.outcome() == null && pos.legalMoves().length > 0) {
            int m = players[pos.sideToMove()].choose(pos);
            if (n == moves.length) {
                moves = Arrays.copyOf(moves, 2 * n);
            }
            moves[n++] = m;
            pos.make(m);
        }
        return Arrays.copyOf(moves, n);
    }
}
