package sternhalma.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import sternhalma.engine.Move;
import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.tablebase.RaceTable;
import sternhalma.tablebase.Rows;

/**
 * Measurement tool of experiment E03 (docs/baseline-round2-plan.md): plays self-play games of a spec from the
 * arena's openings and runs the S1 criterion ({@link RaceAdjudication}) at every ply until it fires. Reports how
 * often S1 fires, at which ply, how many plies it saves, how many plies are passed (race) plies, and whether the game
 * played to the end agrees with every verdict. After a verdict it also asserts the front-advance bound of the
 * soundness statement after every move of the predicted loser.
 *
 * <pre>
 *   java -cp engine/build/classes/java/main:tablebase/build/classes/java/main:search/build/classes/java/main:\
 *       search/build/classes/java/test sternhalma.search.RaceReplay '&lt;spec&gt;' &lt;games&gt; &lt;seed&gt; [threads] [--attack] [--exhaustive N] [--plans]
 * </pre>
 */
public final class RaceReplay {

    /** A game and what S1 said about it. */
    public record Game(int index, int[] opening, int[] moves, Outcome outcome, int plies, int passedPlies,
            int firstFire, RaceAdjudication.Verdict verdict, String violation) {

        /** The position where S1 first fired (replayed), or null. */
        public Position firePosition(RuleSet rules) {
            if (firstFire < 0) {
                return null;
            }
            Position pos = Position.initial(rules);
            for (int i = 0; i < firstFire; i++) {
                pos.make(moves[i]);
            }
            return pos;
        }

        boolean labelCorrect() {
            return verdict == null || (outcome.hasWinner() && outcome.winner() == verdict.winner());
        }

        boolean endPlyKept() {
            return verdict == null || (labelCorrect() && plies <= verdict.endPly());
        }
    }

    private RaceReplay() {
    }

    /**
     * Plays one self-play game of {@code spec} from {@code opening} (a move list from the initial position), running
     * S1 before every move until it fires. {@code moves} of the result holds the whole game including the opening.
     */
    public static Game play(int index, PlayerSpec spec, RuleSet rules, int[] opening, RaceAdjudication adjudication) {
        Position pos = Position.initial(rules);
        for (int m : opening) {
            pos.make(m);
        }
        AlphaBetaPlayer[] players = {spec.create(), spec.create()};
        List<Integer> moves = new ArrayList<>();
        for (int m : opening) {
            moves.add(m);
        }
        int passedPlies = 0;
        int firstFire = -1;
        RaceAdjudication.Verdict verdict = null;
        int loMoves = 0;
        String violation = null;
        while (true) {
            Outcome outcome = pos.outcome();
            if (outcome != null) {
                return new Game(index, opening, toArray(moves), outcome, pos.ply(), passedPlies, firstFire, verdict,
                        violation);
            }
            if (pos.legalMoves().length == 0) {
                return new Game(index, opening, toArray(moves), pos.noMovesOutcome(), pos.ply(), passedPlies,
                        firstFire, verdict, violation == null ? "no legal move" : violation);
            }
            if (RaceAdjudication.passed(pos)) {
                passedPlies++;
            }
            if (verdict == null) {
                verdict = adjudication.adjudicate(pos);
                if (verdict != null) {
                    firstFire = pos.ply();
                }
            }
            int mover = pos.sideToMove();
            int move = players[mover].choose(pos);
            moves.add(move);
            pos.make(move);
            if (verdict != null && mover != verdict.winner()) {
                loMoves++;
                int w = verdict.winner();
                int front = Integer.MIN_VALUE;
                for (int c : pos.pieces(1 - w)) {
                    front = Math.max(front, Rows.row(w, c));
                }
                if (front > verdict.front() + loMoves && violation == null) {
                    violation = "front bound violated at ply " + pos.ply() + ": front " + front + " > "
                            + verdict.front() + " + " + loMoves;
                }
            }
        }
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    /** The arena opening of pair {@code index} for {@code seed}: 4 random plies. */
    public static int[] opening(RuleSet rules, long seed, int index) {
        Position pos = Arena.opening(rules, seed, index, 4);
        int[] moves = new int[pos.ply()];
        // Arena.opening does not expose its moves: replay by re-drawing the same random sequence
        java.util.Random random = new java.util.Random(seed * 1_000_003L + index);
        Position again = Position.initial(rules);
        for (int i = 0; i < 4; i++) {
            int[] legal = again.legalMoves();
            moves[i] = legal[random.nextInt(legal.length)];
            again.make(moves[i]);
        }
        if (!again.equals(pos)) {
            throw new IllegalStateException("opening replay differs from Arena.opening");
        }
        return moves;
    }

    /** Plays {@code games} games on {@code threads} threads and returns them in index order. */
    public static List<Game> run(String spec, int games, long seed, int threads) {
        PlayerSpec p = PlayerSpec.parse(spec);
        RuleSet rules = RuleSet.defaults(2);
        RaceTable table = RaceTable.rows2();
        List<Future<Game>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads))) {
            for (int g = 0; g < games; g++) {
                int index = g;
                futures.add(pool.submit(() -> play(index, p, rules, opening(rules, seed, index),
                        new RaceAdjudication(table))));
            }
            List<Game> result = new ArrayList<>();
            for (Future<Game> f : futures) {
                try {
                    result.add(f.get());
                } catch (Exception e) {
                    throw new IllegalStateException("game failed", e);
                }
            }
            return result;
        }
    }

    /** The summary lines of a run. */
    public static String summary(String spec, long seed, List<Game> games) {
        int n = games.size();
        int fired = 0;
        int wrong = 0;
        int loserWon = 0;
        int noWinner = 0;
        int late = 0;
        int violations = 0;
        long plies = 0;
        long passed = 0;
        long saved = 0;
        long firePlySum = 0;
        int wins = 0;
        int maxWinnerMoves = 0;
        int[] winnerMovesHistogram = new int[32];
        for (Game g : games) {
            plies += g.plies();
            passed += g.passedPlies();
            if (g.outcome().hasWinner()) {
                wins++;
            }
            if (g.verdict() != null) {
                fired++;
                firePlySum += g.firstFire();
                saved += g.plies() - g.firstFire();
                maxWinnerMoves = Math.max(maxWinnerMoves, g.verdict().winnerMoves());
                winnerMovesHistogram[Math.min(31, g.verdict().winnerMoves())]++;
                if (!g.labelCorrect()) {
                    wrong++;
                    if (g.outcome().hasWinner()) {
                        loserWon++;
                    } else {
                        noWinner++;
                    }
                } else if (!g.endPlyKept()) {
                    late++;
                }
            }
            if (g.violation() != null) {
                violations++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "spec=%s seed=%d games=%d decided=%d mean plies=%.1f%n", spec, seed, n,
                wins, plies / (double) n));
        sb.append(String.format(Locale.ROOT, "passed plies: %d of %d (%.1f%%)%n", passed, plies, 100.0 * passed / plies));
        sb.append(String.format(Locale.ROOT,
                "S1 fired in %d of %d games (%.1f%%); mean first firing ply %.1f; plies saved %d (%.2f per game, %.1f%% of all plies)%n",
                fired, n, 100.0 * fired / n, fired == 0 ? Double.NaN : firePlySum / (double) fired, saved,
                saved / (double) n, 100.0 * saved / plies));
        sb.append(String.format(Locale.ROOT,
                "games ended otherwise than predicted: %d of %d (predicted loser won: %d, no winner: %d; the players, not S1, "
                        + "play these games out, see --attack for the plan against the recorded moves); "
                        + "won by the predicted winner after the predicted ply: %d; front-bound violations: %d%n",
                wrong, fired, loserWon, noWinner, late, violations));
        sb.append(String.format(Locale.ROOT, "one-sided 95%% Clopper-Pearson upper bound of the label error: %.4f%n",
                clopperPearsonUpper(wrong, fired)));
        sb.append("winner plan lengths (moves): ").append(Arrays.toString(Arrays.copyOf(winnerMovesHistogram,
                maxWinnerMoves + 1))).append(String.format("%n"));
        return sb.toString();
    }

    /** One-sided 95% Clopper-Pearson upper bound for {@code k} errors in {@code n} trials (1.0 if n == 0). */
    public static double clopperPearsonUpper(int k, int n) {
        if (n == 0) {
            return 1.0;
        }
        if (k >= n) {
            return 1.0;
        }
        // the smallest p with P(X <= k | n, p) <= 0.05, by bisection
        double lo = 0;
        double hi = 1;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (binomialCdf(k, n, mid) > 0.05) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    private static double binomialCdf(int k, int n, double p) {
        double sum = 0;
        for (int i = 0; i <= k; i++) {
            sum += Math.exp(logChoose(n, i) + i * Math.log(p) + (n - i) * Math.log1p(-p));
        }
        return sum;
    }

    private static double logChoose(int n, int k) {
        double v = 0;
        for (int i = 1; i <= k; i++) {
            v += Math.log(n - k + i) - Math.log(i);
        }
        return v;
    }

    /** The result of attacking one verdict with {@link RaceAdversary}. */
    record Attack(int game, int loMoves, RaceAdversary.Result exhaustive, List<RaceAdversary.Result> lines) {
        boolean sound() {
            return (exhaustive == null || exhaustive.sound()) && lines.stream().allMatch(RaceAdversary.Result::sound);
        }
    }

    /**
     * Attacks every verdict: exhaustively when the loser has at most {@code exhaustiveLoMoves} moves (node cap
     * {@code cap}), and along five complete lines in every case (the recorded game, a front-maximiser, the chaser,
     * a random loser and BEST at 2000 nodes).
     */
    static List<Attack> attack(List<Game> games, RuleSet rules, int exhaustiveLoMoves, long cap, int threads) {
        List<Future<Attack>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads))) {
            for (Game g : games) {
                if (g.verdict() == null) {
                    continue;
                }
                futures.add(pool.submit(() -> {
                    RaceAdjudication.Verdict v = g.verdict();
                    Position fire = g.firePosition(rules);
                    boolean wToMove = fire.sideToMove() == v.winner();
                    int loMoves = wToMove ? v.winnerMoves() - 1 : v.winnerMoves();
                    List<RaceAdversary.Result> lines = new ArrayList<>();
                    lines.add(RaceAdversary.recorded(fire, v, g.moves(), RaceAdversary.frontMaximiser(v.winner())));
                    lines.add(RaceAdversary.line(fire, v, RaceAdversary.frontMaximiser(v.winner())));
                    lines.add(RaceAdversary.line(fire, v, RaceAdversary.chaser(v)));
                    lines.add(RaceAdversary.line(fire, v, RaceAdversary.random(g.index())));
                    lines.add(RaceAdversary.line(fire, v,
                            PlayerSpec.parse(Strategies.BEST_SPEC + ",nodes=2000,tt=14").create()));
                    RaceAdversary.Result exhaustive = loMoves <= exhaustiveLoMoves
                            ? RaceAdversary.exhaustive(fire, v, cap) : null;
                    return new Attack(g.index(), loMoves, exhaustive, lines);
                }));
            }
            List<Attack> result = new ArrayList<>();
            for (Future<Attack> f : futures) {
                try {
                    result.add(f.get());
                } catch (Exception e) {
                    throw new IllegalStateException("attack failed", e);
                }
            }
            return result;
        }
    }

    static String attackSummary(List<Attack> attacks, int exhaustiveLoMoves, long cap) {
        int exhaustive = 0;
        int capped = 0;
        long nodes = 0;
        long leaves = 0;
        int failures = 0;
        int[] loMoves = new int[32];
        for (Attack a : attacks) {
            loMoves[Math.min(31, a.loMoves())]++;
            if (!a.sound()) {
                failures++;
            }
            if (a.exhaustive() != null) {
                exhaustive++;
                nodes += a.exhaustive().nodes();
                leaves += a.exhaustive().leaves();
                if (a.exhaustive().capped()) {
                    capped++;
                }
            }
        }
        int max = 0;
        for (int i = 0; i < loMoves.length; i++) {
            if (loMoves[i] > 0) {
                max = i;
            }
        }
        return String.format(Locale.ROOT,
                "adversary: %d verdicts attacked along 5 lines each; exhaustive on %d verdicts with <= %d loser moves "
                        + "(%d hit the cap of %d nodes): %d nodes, %d leaves; failures: %d; loser moves histogram %s%n",
                attacks.size(), exhaustive, exhaustiveLoMoves, capped, cap, nodes, leaves, failures,
                Arrays.toString(Arrays.copyOf(loMoves, max + 1)));
    }

    public static void main(String[] args) {
        String spec = args[0];
        int games = Integer.parseInt(args[1]);
        long seed = Long.parseLong(args[2]);
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : Runtime.getRuntime().availableProcessors();
        boolean attack = Arrays.asList(args).contains("--attack");
        boolean plans = Arrays.asList(args).contains("--plans");
        int exhaustiveLoMoves = 4;
        int at = Arrays.asList(args).indexOf("--exhaustive");
        if (at >= 0) {
            exhaustiveLoMoves = Integer.parseInt(args[at + 1]);
        }
        long t0 = System.nanoTime();
        List<Game> result = run(spec, games, seed, threads);
        for (Game g : result) {
            if (g.verdict() != null || g.violation() != null) {
                RaceAdjudication.Verdict v = g.verdict();
                System.out.printf(Locale.ROOT, "game %d: plies=%d outcome=%s fire=%d winner=%s endPly=%s moves=%s lb=%s %s%s%n",
                        g.index(), g.plies(), g.outcome().hasWinner() ? "win " + g.outcome().winner() : g.outcome().reason(),
                        g.firstFire(), v == null ? "-" : v.winner(), v == null ? "-" : v.endPly(),
                        v == null ? "-" : v.winnerMoves(), v == null ? "-" : v.loserBound(),
                        g.labelCorrect() ? (g.endPlyKept() ? "ok" : "LATE") : "WRONG",
                        g.violation() == null ? "" : " VIOLATION " + g.violation());
                if ((plans || !g.labelCorrect()) && v != null) {
                    StringBuilder sb = new StringBuilder();
                    for (Solitaire.Step s : v.plan().steps()) {
                        sb.append(Move.toString(s.move())).append(' ');
                    }
                    System.out.println("  plan: " + sb);
                    // what the players actually did after the verdict: the winner's and the loser's moves
                    StringBuilder played = new StringBuilder();
                    for (int i = g.firstFire(); i < g.moves().length; i++) {
                        played.append(i % 2 == v.winner() ? "W:" : "L:").append(Move.toString(g.moves()[i])).append(' ');
                    }
                    System.out.println("  played: " + played);
                }
            }
        }
        System.out.print(summary(spec, seed, result));
        System.out.printf(Locale.ROOT, "games elapsed %.1f s on %d threads%n", (System.nanoTime() - t0) / 1e9, threads);
        if (result.stream().anyMatch(g -> !g.labelCorrect() || g.violation() != null)) {
            System.out.println("NOTE: a game ended otherwise than predicted or an invariant was violated (see above)");
        }
        if (attack) {
            long t1 = System.nanoTime();
            List<Attack> attacks = attack(result, RuleSet.defaults(2), exhaustiveLoMoves, 10_000_000L, threads);
            for (Attack a : attacks) {
                if (!a.sound()) {
                    System.out.println("FAILURE game " + a.game() + ": " + a);
                }
            }
            System.out.print(attackSummary(attacks, exhaustiveLoMoves, 10_000_000L));
            System.out.printf(Locale.ROOT, "attack elapsed %.1f s%n", (System.nanoTime() - t1) / 1e9);
        }
    }
}
