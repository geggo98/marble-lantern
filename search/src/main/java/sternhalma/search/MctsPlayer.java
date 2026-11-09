package sternhalma.search;

import java.util.Objects;

import sternhalma.engine.Position;
import sternhalma.nn.ForwardPass;

/**
 * {@link Mcts} as a player. With a {@link RaceMove} (option {@code race}) a passed two-player position is played by the
 * race module when it offers a legal move, as {@link AlphaBetaPlayer} does: such a move costs no simulation and is
 * counted in {@link #raceMoves()}. Every other move is the most visited (or sampled) move of a search of
 * {@code sims} simulations; it is always one of {@code Position.legalMoves()}.
 *
 * <p>Counts simulations, network evaluations and time for the arena reports. Not thread-safe; one instance per game
 * and thread (see {@link Mcts}).
 */
public final class MctsPlayer implements Player {

    private final Mcts mcts;
    private final RaceMove race;
    private final String name;
    private long totalSims;
    private long totalNanos;
    private long moves;
    private long raceMoves;
    private MctsResult lastResult;

    /** @param race race play before the search, or null */
    public MctsPlayer(Mcts mcts, RaceMove race, String name) {
        this.mcts = Objects.requireNonNull(mcts, "mcts");
        this.race = race;
        this.name = Objects.requireNonNull(name, "name");
    }

    /** A player for the options: its search, its race module (rows table of {@code racerows}) and a name. */
    public static MctsPlayer create(ForwardPass network, MctsOptions options, long seed, String name) {
        RaceMove raceMove = options.race() == 0 ? null : new RaceMove(MctsSpec.raceTable(options), options.race());
        return new MctsPlayer(new Mcts(network, options, seed), raceMove, name);
    }

    @Override
    public int choose(Position position) {
        long t0 = System.nanoTime();
        if (race != null && position.rules().players() == 2) {
            int move = race.choose(position);
            if (move >= 0) {
                totalNanos += System.nanoTime() - t0;
                moves++;
                raceMoves++;
                lastResult = null;
                return move;
            }
        }
        MctsResult result = mcts.search(position);
        totalNanos += System.nanoTime() - t0;
        totalSims += result.sims();
        moves++;
        lastResult = result;
        return result.move();
    }

    @Override
    public String name() {
        return name;
    }

    public Mcts mcts() {
        return mcts;
    }

    /** The result of the last search, or null if the race module chose the last move or nothing was chosen yet. */
    public MctsResult lastResult() {
        return lastResult;
    }

    /** Simulations run in all searches so far. */
    public long totalSims() {
        return totalSims;
    }

    /** Wall-clock time spent choosing moves so far. */
    public long totalNanos() {
        return totalNanos;
    }

    /** Number of moves chosen so far. */
    public long moves() {
        return moves;
    }

    /** Number of moves chosen by the race module instead of the search. */
    public long raceMoves() {
        return raceMoves;
    }
}
