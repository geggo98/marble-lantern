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
            double nodesPerMoveA, double nodesPerMoveB) {

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "A=%s%nB=%s%npairs=%d games=%d  A wins=%d draws=%d B wins=%d  avg plies=%.1f%n"
                            + "score(A)=%.4f +- %.4f  Elo(A-B)=%+.1f +- %.1f (95%%)%n"
                            + "A: %.0f nodes/move, %.0f nodes/s   B: %.0f nodes/move, %.0f nodes/s%n"
                            + "RESULT elo=%.1f ci=%.1f score=%.4f pairs=%d",
                    a.text(), b.text(), pairs, 2 * pairs, aWins, draws, bWins, averagePlies,
                    score, scoreStandardError, elo, eloConfidence95,
                    nodesPerMoveA, nodesPerSecondA, nodesPerMoveB, nodesPerSecondB,
                    elo, eloConfidence95, score, pairs);
        }
    }

    private record GameData(double scoreA, int plies, boolean aWon, boolean bWon,
            long nodesA, long nanosA, long movesA, long nodesB, long nanosB, long movesB) {
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
        RuleSet rules = RuleSet.defaults(2);
        List<Future<GameData[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads))) {
            for (int pair = 0; pair < pairs; pair++) {
                int index = pair;
                futures.add(pool.submit(() -> {
                    Position start = opening(rules, seed, index, openingPlies);
                    return new GameData[] {play(start, a, b, true), play(start, a, b, false)};
                }));
            }
            double[] pairScores = new double[pairs];
            int aWins = 0;
            int bWins = 0;
            int draws = 0;
            double plies = 0;
            long nodesA = 0;
            long nanosA = 0;
            long movesA = 0;
            long nodesB = 0;
            long nanosB = 0;
            long movesB = 0;
            for (int pair = 0; pair < pairs; pair++) {
                GameData[] games = get(futures.get(pair));
                pairScores[pair] = (games[0].scoreA() + games[1].scoreA()) / 2.0;
                for (GameData g : games) {
                    aWins += g.aWon() ? 1 : 0;
                    bWins += g.bWon() ? 1 : 0;
                    draws += !g.aWon() && !g.bWon() ? 1 : 0;
                    plies += g.plies();
                    nodesA += g.nodesA();
                    nanosA += g.nanosA();
                    movesA += g.movesA();
                    nodesB += g.nodesB();
                    nanosB += g.nanosB();
                    movesB += g.movesB();
                }
            }
            double score = Elo.mean(pairScores);
            double se = Elo.standardError(pairScores);
            return new Report(a, b, pairs, score, se, Elo.fromScore(score), Elo.confidence95(score, se),
                    aWins, draws, bWins, plies / (2.0 * pairs),
                    nodesA / (nanosA / 1e9), nodesB / (nanosB / 1e9),
                    (double) nodesA / movesA, (double) nodesB / movesB);
        }
    }

    private static GameData[] get(Future<GameData[]> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException("game failed", e);
        }
    }

    private static GameData play(Position start, PlayerSpec a, PlayerSpec b, boolean aFirst) {
        AlphaBetaPlayer playerA = a.create();
        AlphaBetaPlayer playerB = b.create();
        GameRunner.Result result = aFirst
                ? GameRunner.play(start, playerA, playerB)
                : GameRunner.play(start, playerB, playerA);
        Outcome outcome = result.outcome();
        int indexA = aFirst ? 0 : 1;
        double scoreA = (1.0 + outcome.value(indexA)) / 2.0;
        boolean aWon = outcome.hasWinner() && outcome.winner() == indexA;
        boolean bWon = outcome.hasWinner() && outcome.winner() != indexA;
        return new GameData(scoreA, result.plies(), aWon, bWon,
                playerA.totalNodes(), playerA.totalNanos(), playerA.moves(),
                playerB.totalNodes(), playerB.totalNanos(), playerB.moves());
    }

    public static void main(String[] args) {
        String a = null;
        String b = null;
        int pairs = 50;
        long seed = 1;
        int openingPlies = 4;
        int threads = Runtime.getRuntime().availableProcessors();
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
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (a == null || b == null) {
            throw new IllegalArgumentException("usage: Arena --a <spec> --b <spec> [--pairs N] [--seed S] "
                    + "[--opening-plies K] [--threads T]");
        }
        System.out.println(match(PlayerSpec.parse(a), PlayerSpec.parse(b), pairs, seed, openingPlies, threads));
    }
}
