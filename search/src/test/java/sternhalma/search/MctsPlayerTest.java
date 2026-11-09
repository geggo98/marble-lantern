package sternhalma.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.ForwardPass;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;

/** The player: legal moves, and the same games whatever the number of threads that play them. */
class MctsPlayerTest {

    /** The moves of one game of two MCTS players with noise and sampling, from a seeded random opening. */
    private static List<Integer> game(ForwardPass net, MctsOptions options, int index) {
        Position start = StrengthOpening.opening(index);
        Player[] players = {MctsPlayer.create(net, options, 100 + index, "a"), MctsPlayer.create(net, options, 200 + index, "b")};
        List<Integer> moves = new ArrayList<>();
        Player[] recording = new Player[2];
        for (int i = 0; i < 2; i++) {
            Player p = players[i];
            recording[i] = new Player() {
                @Override
                public int choose(Position position) {
                    int m = p.choose(position);
                    moves.add(m);
                    return m;
                }

                @Override
                public String name() {
                    return p.name();
                }
            };
        }
        GameRunner.Result r = GameRunner.play(start, recording);
        assertTrue(r.plies() > 0);
        moves.add(-r.plies());
        return moves;
    }

    /** Seeded random openings. */
    static final class StrengthOpening {
        static Position opening(int index) {
            java.util.Random random = new java.util.Random(index * 31L + 5);
            Position pos = Position.initial(RuleSet.defaults(2));
            for (int i = 0; i < 4; i++) {
                int[] moves = pos.legalMoves();
                pos.make(moves[random.nextInt(moves.length)]);
            }
            return pos;
        }
    }

    private static List<List<Integer>> play(ForwardPass net, MctsOptions options, int games, int threads) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<List<Integer>>> futures = new ArrayList<>();
            for (int g = 0; g < games; g++) {
                int index = g;
                futures.add(pool.submit(() -> game(net, options, index)));
            }
            List<List<Integer>> result = new ArrayList<>();
            for (Future<List<Integer>> f : futures) {
                result.add(f.get());
            }
            return result;
        }
    }

    @Test
    void theGamesDoNotDependOnTheNumberOfThreadsThatPlayThem() throws Exception {
        MctsOptions options = MctsOptions.defaults().withSims(12).withBatch(6).withNoise(0.25).withTemperature(0.8)
                .withRace(1);
        HashedNetwork hashed = new HashedNetwork(2, 3);
        List<List<Integer>> one = play(hashed, options, 8, 1);
        List<List<Integer>> four = play(hashed, options, 8, 4);
        List<List<Integer>> again = play(hashed, options, 8, 3);
        assertEquals(one, four);
        assertEquals(one, again);
        // a real network, shared between the threads (the scalar network is thread-safe)
        Network real = new Network(Parameters.init(NetworkConfig.tiny(2), 4));
        MctsOptions short6 = options.withSims(6).withBatch(3);
        assertEquals(play(real, short6, 4, 1), play(real, short6, 4, 4));
    }

    @Test
    void everyMoveOfEveryGameIsLegalAndTheGamesEnd() {
        HashedNetwork net = new HashedNetwork(2, 9);
        for (int index = 0; index < 6; index++) {
            MctsPlayer a = MctsPlayer.create(net, MctsOptions.defaults().withSims(10).withBatch(5), 1, "a");
            MctsPlayer b = MctsPlayer.create(net, MctsOptions.defaults().withSims(10).withBatch(5).withRace(0), 1, "b");
            GameRunner.Result r = GameRunner.play(StrengthOpening.opening(index), a, b); // throws on an illegal move
            Outcome o = r.outcome();
            assertTrue(o != null);
            assertEquals(r.moves()[0], a.moves());
            assertEquals(r.moves()[1], b.moves());
            assertEquals(a.moves() - a.raceMoves(), a.totalSims() / 10);
            assertEquals(0, b.raceMoves());
        }
    }

    @Test
    void theRaceModuleMovesAreCountedAndCostNoSimulation() {
        HashedNetwork net = new HashedNetwork(2, 9);
        int total = 0;
        for (int index = 0; index < 6; index++) {
            MctsPlayer a = MctsPlayer.create(net, MctsOptions.defaults().withSims(10).withBatch(5), 1, "a");
            MctsPlayer b = MctsPlayer.create(net, MctsOptions.defaults().withSims(10).withBatch(5), 1, "b");
            GameRunner.play(StrengthOpening.opening(index), a, b);
            total += (int) (a.raceMoves() + b.raceMoves());
            assertEquals(10L * (a.moves() - a.raceMoves()), a.totalSims(), "a race move costs no simulation");
        }
        assertTrue(total >= 0);
    }
}
