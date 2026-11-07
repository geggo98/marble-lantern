package sternhalma.selfplay;

import sternhalma.engine.Position;
import sternhalma.search.Evaluator;
import sternhalma.search.RaceAdjudication;
import sternhalma.search.Scores;
import sternhalma.search.SearchResult;
import sternhalma.tablebase.RaceCounts;

/**
 * Layered adjudication of self-play games (round-2 plan, E05; docs/training.md, "Self-play data tool").
 *
 * <ol>
 *   <li><b>S1</b> ({@link RaceAdjudication}): exact; its verdict is the game value of the position and is never
 *       replaced by the result of a game played on by an imperfect player.</li>
 *   <li><b>Score margin:</b> the same player reported {@code |score| >= margin} with the same sign on
 *       {@code marginMoves} consecutive own moves, every score of kind {@link Scores.Kind#ORDINARY} or
 *       {@link Scores.Kind#MATE}; the label is the side the scores favour. A move without a search score (race
 *       play) or with a {@link Scores.Kind#RACE} score is "no information" and resets that player's streak.</li>
 *   <li><b>Static evaluation</b> (off by default): sigmoid(eval / 100) >= threshold for the same side on
 *       {@code evalPlies} consecutive positions, ORDINARY values only.</li>
 * </ol>
 *
 * <p>Label policy: layers 2 and 3 never fire in a passed position ({@link RaceAdjudication#passed}); there only S1
 * may label. Two players only: the constructor refuses other player counts.
 *
 * <p>Not thread-safe (S1 keeps solitaire scratch space); one instance per thread, one {@link Tracker} per game.
 */
public final class Adjudication {

    /** Which layer produced a label; the code is stored in the sample files. */
    public enum Layer {
        /** The game was played to its end; the label is the outcome. */
        NONE(0),
        S1(1),
        MARGIN(2),
        EVAL(3);

        public final int code;

        Layer(int code) {
            this.code = code;
        }

        public static Layer of(int code) {
            for (Layer l : values()) {
                if (l.code == code) {
                    return l;
                }
            }
            throw new IllegalArgumentException("no layer " + code);
        }
    }

    /**
     * @param s1             layer 1 on
     * @param margin         layer 2 score margin, 0 = off
     * @param marginMoves    consecutive own moves for layer 2
     * @param evalThreshold  layer 3 win probability, 0 = off
     * @param evalPlies      consecutive plies for layer 3
     */
    public record Config(boolean s1, int margin, int marginMoves, double evalThreshold, int evalPlies) {

        public static final Config DEFAULT = new Config(true, 500, 2, 0, 4);
        public static final Config OFF = new Config(false, 0, 2, 0, 4);

        public Config {
            if (margin < 0 || marginMoves < 1 || evalPlies < 1 || evalThreshold < 0 || evalThreshold >= 1) {
                throw new IllegalArgumentException("bad adjudication config");
            }
            if (evalThreshold > 0 && evalThreshold <= 0.5) {
                throw new IllegalArgumentException("evalThreshold must exceed 0.5");
            }
        }

        public boolean any() {
            return s1 || margin > 0 || evalThreshold > 0;
        }

        public String describe() {
            return "s1=" + (s1 ? 1 : 0) + ",margin=" + margin + ",marginMoves=" + marginMoves
                    + ",eval=" + evalThreshold + ",evalPlies=" + evalPlies;
        }
    }

    /** A firing: {@code winner} wins; {@code ply} is the ply of the position the label applies to. */
    public record Firing(Layer layer, int ply, int winner, int endPly) {
    }

    private final Config config;
    private final RaceAdjudication s1;
    private final Evaluator evaluator;

    /**
     * @param players   the player count of the games; anything but 2 is refused
     * @param table     race counts for S1's plan search, or null
     * @param evaluator static evaluator for layer 3, or null when layer 3 is off
     */
    public Adjudication(Config config, int players, RaceCounts table, Evaluator evaluator) {
        if (players != 2 && config.any()) {
            throw new IllegalArgumentException("adjudication supports two players only, got " + players);
        }
        if (config.evalThreshold() > 0 && evaluator == null) {
            throw new IllegalArgumentException("layer 3 needs an evaluator");
        }
        this.config = config;
        this.s1 = config.s1() ? new RaceAdjudication(table) : null;
        this.evaluator = evaluator;
    }

    public Config config() {
        return config;
    }

    public Tracker tracker() {
        return new Tracker();
    }

    /** The state of one game. Feed it every position before a move and every search result after it. */
    public final class Tracker {
        private final int[] streak = new int[2];
        private final int[] streakSign = new int[2];
        private int evalStreak;
        private int evalWinner = -1;
        private Firing s1Firing;
        private Firing marginFiring;
        private Firing evalFiring;
        private int raceScoresIgnored;

        /**
         * Layers 1 and 3 on the position before the side to move chooses. Returns the first firing of a layer that
         * had not fired yet in this game, or null.
         */
        public Firing beforeMove(Position pos) {
            Firing fired = null;
            if (s1 != null && s1Firing == null && RaceAdjudication.passed(pos)) {
                RaceAdjudication.Verdict v = s1.adjudicate(pos);
                if (v != null) {
                    s1Firing = new Firing(Layer.S1, pos.ply(), v.winner(), v.endPly());
                    fired = s1Firing;
                }
            }
            if (config.evalThreshold() > 0 && evalFiring == null) {
                int e = evaluator.evaluate(pos);
                int stm = pos.sideToMove();
                int winner = -1;
                if (Scores.kind(e) == Scores.Kind.ORDINARY) {
                    double p = 1 / (1 + Math.exp(-e / 100.0));
                    winner = p >= config.evalThreshold() ? stm : 1 - p >= config.evalThreshold() ? 1 - stm : -1;
                }
                if (winner >= 0 && winner == evalWinner) {
                    evalStreak++;
                } else {
                    evalWinner = winner;
                    evalStreak = winner >= 0 ? 1 : 0;
                }
                if (evalStreak >= config.evalPlies() && !RaceAdjudication.passed(pos) && fired == null) {
                    evalFiring = new Firing(Layer.EVAL, pos.ply(), evalWinner, -1);
                    fired = evalFiring;
                }
            }
            return fired;
        }

        /**
         * Layer 2 after {@code mover} played and the game is not over; {@code after} is the position after the move,
         * {@code result} the mover's search result or null (race play). Returns the first firing or null.
         */
        public Firing afterMove(Position after, int mover, SearchResult result) {
            if (config.margin() == 0 || marginFiring != null) {
                return null;
            }
            int sign = 0;
            if (result != null) {
                Scores.Kind kind = Scores.kind(result.score());
                if (kind == Scores.Kind.RACE) {
                    raceScoresIgnored++;
                } else if (Math.abs(result.score()) >= config.margin()) {
                    sign = Integer.signum(result.score());
                }
            }
            if (sign != 0 && sign == streakSign[mover]) {
                streak[mover]++;
            } else {
                streakSign[mover] = sign;
                streak[mover] = sign == 0 ? 0 : 1;
            }
            if (streak[mover] >= config.marginMoves() && after.outcome() == null && !RaceAdjudication.passed(after)) {
                marginFiring = new Firing(Layer.MARGIN, after.ply(), sign > 0 ? mover : 1 - mover, -1);
                return marginFiring;
            }
            return null;
        }

        public Firing s1() {
            return s1Firing;
        }

        public Firing margin() {
            return marginFiring;
        }

        public Firing eval() {
            return evalFiring;
        }

        /** Search scores in the race band that layer 2 saw and ignored. */
        public int raceScoresIgnored() {
            return raceScoresIgnored;
        }
    }
}
