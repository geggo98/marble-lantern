package sternhalma.search;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;

/**
 * Plays a game between players from a start position and rejects illegal moves. Measures the thread CPU time
 * and the wall time of every move choice per player.
 */
public final class GameRunner {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME = cpuTimeAvailable();

    /**
     * The end of a game and the number of plies played, with the cost of each player's moves.
     *
     * @param cpuNanos  per player: thread CPU time spent in {@link Player#choose}, -1 if the JVM cannot
     *                  measure thread CPU time
     * @param wallNanos per player: wall-clock time spent in {@link Player#choose}
     * @param moves     per player: number of moves chosen
     */
    public record Result(Outcome outcome, int plies, long[] cpuNanos, long[] wallNanos, int[] moves) {

        /** Thread CPU milliseconds per move of {@code player}, NaN if unknown or the player did not move. */
        public double cpuMillisPerMove(int player) {
            return cpuNanos[player] < 0 || moves[player] == 0 ? Double.NaN : cpuNanos[player] / 1e6 / moves[player];
        }
    }

    private GameRunner() {
    }

    /** Whether {@link Result#cpuNanos()} holds measured values on this JVM. */
    public static boolean measuresCpuTime() {
        return CPU_TIME;
    }

    private static boolean cpuTimeAvailable() {
        if (!THREADS.isCurrentThreadCpuTimeSupported()) {
            return false;
        }
        if (!THREADS.isThreadCpuTimeEnabled()) {
            THREADS.setThreadCpuTimeEnabled(true);
        }
        return THREADS.isThreadCpuTimeEnabled();
    }

    /** CPU time of the current thread in nanoseconds, or 0 if not measurable. */
    static long cpuNow() {
        return CPU_TIME ? THREADS.getCurrentThreadCpuTime() : 0;
    }

    /**
     * Plays until the game ends. {@code players[i]} controls player i. The start position is not modified.
     *
     * @throws IllegalMoveException if a player returns a move that is not legal
     */
    public static Result play(Position start, Player... players) {
        if (players.length != start.rules().players()) {
            throw new IllegalArgumentException("need " + start.rules().players() + " players, got " + players.length);
        }
        long[] cpu = new long[players.length];
        long[] wall = new long[players.length];
        int[] moves = new int[players.length];
        Position position = start.copy();
        while (true) {
            Outcome outcome = position.outcome();
            if (outcome != null) {
                return result(outcome, position.ply(), cpu, wall, moves);
            }
            int[] legal = position.legalMoves();
            if (legal.length == 0) {
                return result(position.noMovesOutcome(), position.ply(), cpu, wall, moves);
            }
            int side = position.sideToMove();
            Player player = players[side];
            long c0 = cpuNow();
            long w0 = System.nanoTime();
            int move = player.choose(position);
            wall[side] += System.nanoTime() - w0;
            cpu[side] += cpuNow() - c0;
            moves[side]++;
            if (!contains(legal, move)) {
                throw new IllegalMoveException(player.name(), move, position);
            }
            position.make(move);
        }
    }

    private static Result result(Outcome outcome, int plies, long[] cpu, long[] wall, int[] moves) {
        if (!CPU_TIME) {
            java.util.Arrays.fill(cpu, -1);
        }
        return new Result(outcome, plies, cpu, wall, moves);
    }

    private static boolean contains(int[] moves, int move) {
        for (int m : moves) {
            if (m == move) {
                return true;
            }
        }
        return false;
    }
}
