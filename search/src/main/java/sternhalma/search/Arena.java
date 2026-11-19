package sternhalma.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Plays paired games between two player specs and reports the score and the Elo difference.
 *
 * <p>Each pair starts from the same random opening and is played twice with the colours swapped, which
 * removes most of the first-move advantage and of the opening luck from the comparison. A game result is
 * the score {@code (1 + value) / 2} of the outcome, so a win is 1, a loss is 0 and a game ended by the move
 * limit counts by its distance score (docs/rules.md).
 *
 * <p>Cost per player is reported twice: wall time and thread CPU time per move (measured by {@link GameRunner}
 * around each move choice on the thread that plays the game). Criteria such as "not slower" use the CPU column,
 * because wall time under shared load also counts the time the thread waited for a core. The report also
 * suggests the node budget for A that costs as much CPU time as B's budget,
 * {@code nodes(A) = nodes(B) x nps(A) / nps(B)}; that ratio is measured under the arena's own load, and claims
 * use the ratio of {@link Bench} instead.
 *
 * <p>{@code --sprt elo0,elo1} stops the match early once a sequential test on the pair scores (in pair order,
 * so the decision does not depend on the thread count) accepts one hypothesis; see {@link Sprt}. Default off.
 *
 * <pre>
 *   java -cp ... sternhalma.search.Arena --a ab:eval=distance --b ab:eval=rows --pairs 100
 * </pre>
 */
public final class Arena {

    /** The result of a match from the point of view of player A. */
    public record Report(
            PlayerSpec a, PlayerSpec b, int pairs, double score, double scoreStandardError,
            double elo, double eloConfidence95, int aWins, int draws, int bWins,
            double averagePlies, double nodesPerSecondA, double nodesPerSecondB,
            double nodesPerMoveA, double nodesPerMoveB,
            double cpuMillisPerMoveA, double cpuMillisPerMoveB, double wallMillisPerMoveA, double wallMillisPerMoveB,
            double nodesPerCpuSecondA, double nodesPerCpuSecondB, String sprt) {

        /**
         * The node budget for A that costs as much CPU time per move as B's budget, from this run's CPU-time
         * nodes/s: {@code nodes(B) x nps(A) / nps(B)}. Measured under the arena's load; NaN without CPU times.
         */
        public double equalCostNodesA() {
            return b.nodes() * nodesPerCpuSecondA / nodesPerCpuSecondB;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "A=%s%nB=%s%npairs=%d games=%d  A wins=%d draws=%d B wins=%d  avg plies=%.1f%n"
                            + "score(A)=%.4f +- %.4f  Elo(A-B)=%+.1f +- %.1f (95%%)%n"
                            + "%s"
                            + "A: %.0f nodes/move, %.0f nodes/s wall, %.0f nodes/CPU-s, %.2f CPU-ms/move, %.2f wall-ms/move%n"
                            + "B: %.0f nodes/move, %.0f nodes/s wall, %.0f nodes/CPU-s, %.2f CPU-ms/move, %.2f wall-ms/move%n"
                            + "equal cost: nodes(A) = nodes(B) x nps(A)/nps(B) = %d x %.3f = %.0f "
                            + "(CPU time under this run's load; use the Bench ratio for claims)%n"
                            + "RESULT elo=%.1f ci=%.1f score=%.4f pairs=%d cpu_ms_a=%.3f cpu_ms_b=%.3f",
                    a.text(), b.text(), pairs, 2 * pairs, aWins, draws, bWins, averagePlies,
                    score, scoreStandardError, elo, eloConfidence95,
                    sprt.isEmpty() ? "" : sprt + String.format("%n"),
                    nodesPerMoveA, nodesPerSecondA, nodesPerCpuSecondA, cpuMillisPerMoveA, wallMillisPerMoveA,
                    nodesPerMoveB, nodesPerSecondB, nodesPerCpuSecondB, cpuMillisPerMoveB, wallMillisPerMoveB,
                    b.nodes(), nodesPerCpuSecondA / nodesPerCpuSecondB, equalCostNodesA(),
                    elo, eloConfidence95, score, pairs, cpuMillisPerMoveA, cpuMillisPerMoveB);
        }
    }

    private record GameData(double scoreA, int plies, boolean aWon, boolean bWon,
            long nodesA, long nanosA, long cpuA, long movesA, long nodesB, long nanosB, long cpuB, long movesB) {
    }

    private Arena() {
    }

    /** The opening of a pair: {@code plies} random legal plies from the start position. */
    static Position opening(RuleSet rules, long seed, int pair, int plies) {
        Random random = new Random(seed * 1_000_003L + pair);
        Position pos = Position.initial(rules);
        for (int i = 0; i < plies; i++) {
            int[] moves = pos.legalMoves();
            pos.make(moves[random.nextInt(moves.length)]);
        }
        return pos;
    }

    public static Report match(PlayerSpec a, PlayerSpec b, int pairs, long seed, int openingPlies, int threads) {
        return match(a, b, pairs, seed, openingPlies, threads, null);
    }

    /**
     * A match of at most {@code pairs} pairs; with an {@link Sprt} (may be null) it stops at the first pair after
     * which the test accepts a hypothesis, and the report covers the pairs played up to there.
     */
    public static Report match(PlayerSpec a, PlayerSpec b, int pairs, long seed, int openingPlies, int threads,
            Sprt sprt) {
        return match(a, b, null, null, pairs, seed, openingPlies, threads, sprt);
    }

    /**
     * {@link #match(PlayerSpec, PlayerSpec, int, long, int, int, Sprt)} with the race tables of each side from its own
     * {@link RaceTableSource}; a null source means the ambient one (system properties, default the exact tables), which is
     * what the overload without sources does. A source is shared by all games of its side, so it must be thread-safe
     * ({@link RaceTableSource#of} is).
     */
    public static Report match(PlayerSpec a, PlayerSpec b, RaceTableSource sourceA, RaceTableSource sourceB, int pairs,
            long seed, int openingPlies, int threads, Sprt sprt) {
        if (a.jumpRule() != b.jumpRule()) {
            throw new IllegalArgumentException("the two sides play different jump rules: " + a.jumpRule() + " and " + b.jumpRule());
        }
        RuleSet rules = RuleSet.defaults(2).withJumpRule(a.jumpRule());
        List<Future<GameData[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads))) {
            for (int pair = 0; pair < pairs; pair++) {
                int index = pair;
                futures.add(pool.submit(() -> {
                    Position start = opening(rules, seed, index, openingPlies);
                    return new GameData[] {play(start, a, b, sourceA, sourceB, true),
                            play(start, a, b, sourceA, sourceB, false)};
                }));
            }
            double[] pairScores = new double[pairs];
            int played = 0;
            double llr = 0;
            int aWins = 0;
            int bWins = 0;
            int draws = 0;
            double plies = 0;
            long nodesA = 0;
            long nanosA = 0;
            long cpuA = 0;
            long movesA = 0;
            long nodesB = 0;
            long nanosB = 0;
            long cpuB = 0;
            long movesB = 0;
            for (int pair = 0; pair < pairs; pair++) {
                GameData[] games = get(futures.get(pair));
                played++;
                pairScores[pair] = (games[0].scoreA() + games[1].scoreA()) / 2.0;
                for (GameData g : games) {
                    aWins += g.aWon() ? 1 : 0;
                    bWins += g.bWon() ? 1 : 0;
                    draws += !g.aWon() && !g.bWon() ? 1 : 0;
                    plies += g.plies();
                    nodesA += g.nodesA();
                    nanosA += g.nanosA();
                    cpuA += g.cpuA();
                    movesA += g.movesA();
                    nodesB += g.nodesB();
                    nanosB += g.nanosB();
                    cpuB += g.cpuB();
                    movesB += g.movesB();
                }
                if (sprt != null) {
                    llr = sprt.llr(pairScores, played);
                    if (sprt.decide(llr) != Sprt.Decision.CONTINUE) {
                        for (int rest = pair + 1; rest < pairs; rest++) {
                            futures.get(rest).cancel(false);
                        }
                        break;
                    }
                }
            }
            double[] scores = java.util.Arrays.copyOf(pairScores, played);
            double score = Elo.mean(scores);
            double se = Elo.standardError(scores);
            boolean cpu = GameRunner.measuresCpuTime();
            return new Report(a, b, played, score, se, Elo.fromScore(score), Elo.confidence95(score, se),
                    aWins, draws, bWins, plies / (2.0 * played),
                    nodesA / (nanosA / 1e9), nodesB / (nanosB / 1e9),
                    (double) nodesA / movesA, (double) nodesB / movesB,
                    cpu ? cpuA / 1e6 / movesA : Double.NaN, cpu ? cpuB / 1e6 / movesB : Double.NaN,
                    nanosA / 1e6 / movesA, nanosB / 1e6 / movesB,
                    cpu ? nodesA / (cpuA / 1e9) : Double.NaN, cpu ? nodesB / (cpuB / 1e9) : Double.NaN,
                    sprt == null ? "" : sprt.describe(llr, played));
        }
    }

    private static GameData[] get(Future<GameData[]> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException("game failed", e);
        }
    }

    private static GameData play(Position start, PlayerSpec a, PlayerSpec b, RaceTableSource sourceA,
            RaceTableSource sourceB, boolean aFirst) {
        AlphaBetaPlayer playerA = sourceA == null ? a.create() : a.create(sourceA);
        AlphaBetaPlayer playerB = sourceB == null ? b.create() : b.create(sourceB);
        GameRunner.Result result = aFirst
                ? GameRunner.play(start, playerA, playerB)
                : GameRunner.play(start, playerB, playerA);
        Outcome outcome = result.outcome();
        int indexA = aFirst ? 0 : 1;
        int indexB = 1 - indexA;
        double scoreA = (1.0 + outcome.value(indexA)) / 2.0;
        boolean aWon = outcome.hasWinner() && outcome.winner() == indexA;
        boolean bWon = outcome.hasWinner() && outcome.winner() != indexA;
        return new GameData(scoreA, result.plies(), aWon, bWon,
                playerA.totalNodes(), result.wallNanos()[indexA], result.cpuNanos()[indexA], result.moves()[indexA],
                playerB.totalNodes(), result.wallNanos()[indexB], result.cpuNanos()[indexB], result.moves()[indexB]);
    }

    /** The source of one side from its two role variants; null (the ambient source) if neither is given. */
    static RaceTableSource side(String eval, String play) {
        if (eval == null && play == null) {
            return null;
        }
        return RaceTableSource.of(eval == null ? "exact" : eval, play == null ? "exact" : play);
    }

    public static void main(String[] args) {
        String a = null;
        String b = null;
        int pairs = 50;
        long seed = 1;
        int openingPlies = 4;
        int threads = Runtime.getRuntime().availableProcessors();
        Sprt sprt = null;
        String[] race = new String[4]; // a-eval, a-play, b-eval, b-play; null = not given
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + args[i]);
            }
            switch (args[i]) {
                case "--a" -> a = args[i + 1];
                case "--b" -> b = args[i + 1];
                case "--pairs" -> pairs = Integer.parseInt(args[i + 1]);
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--opening-plies" -> openingPlies = Integer.parseInt(args[i + 1]);
                case "--threads" -> threads = Integer.parseInt(args[i + 1]);
                case "--sprt" -> sprt = Sprt.parse(args[i + 1]);
                case "--a-race-eval" -> race[0] = args[i + 1];
                case "--a-race-play" -> race[1] = args[i + 1];
                case "--b-race-eval" -> race[2] = args[i + 1];
                case "--b-race-play" -> race[3] = args[i + 1];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (a == null || b == null) {
            throw new IllegalArgumentException("usage: Arena --a <spec> --b <spec> [--pairs N] [--seed S] "
                    + "[--opening-plies K] [--threads T] [--sprt elo0,elo1] "
                    + "[--a-race-eval V] [--a-race-play V] [--b-race-eval V] [--b-race-play V]");
        }
        System.out.println(match(PlayerSpec.parse(a), PlayerSpec.parse(b), side(race[0], race[1]), side(race[2], race[3]),
                pairs, seed, openingPlies, threads, sprt));
    }
}
