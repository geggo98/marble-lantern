package sternhalma.webnet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.nn.Encoder;
import sternhalma.nn.Network;
import sternhalma.nn.NetworkConfig;
import sternhalma.nn.Parameters;
import sternhalma.nn.PlaneSet;
import sternhalma.search.Mcts;
import sternhalma.search.MctsOptions;
import sternhalma.search.MctsResult;

/**
 * The facade core against the synchronous {@link Mcts} with a real (randomly initialised, so reproducible) network, the
 * protocol errors, and the golden files of the node harness.
 */
class WebNetCoreTest {

    private record Case(String name, RuleSet.JumpRule jump, int players, NetworkConfig config) {
    }

    private static final List<Case> CASES = List.of(
            new Case("2p-adjacent", RuleSet.JumpRule.ADJACENT, 2, NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1)),
            new Case("2p-long", RuleSet.JumpRule.LONG, 2, NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V)),
            new Case("3p-adjacent", RuleSet.JumpRule.ADJACENT, 3, NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V)),
            new Case("3p-long", RuleSet.JumpRule.LONG, 3, NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V)));

    /** The network as the page does it: the forward pass on the inputs, the policy split into its two heads. */
    private static int[] forwardAndApply(WebNetCore core, Network network, int n) {
        NetworkConfig config = network.config();
        float[] policy = new float[n * Encoder.POLICY_SIZE];
        float[] values = new float[n * config.players()];
        network.forward(core.inputs(), n, policy, values);
        float[] from = new float[n * 121];
        float[] to = new float[n * 121];
        for (int i = 0; i < n * 121; i++) {
            from[i] = policy[2 * i];
            to[i] = policy[2 * i + 1];
        }
        return new int[] {core.apply(from, to, values)};
    }

    @Test
    void playsLikeTheSynchronousSearchInAllFourVariantsAtBatchOneAndFour() {
        for (Case c : CASES) {
            Network network = new Network(Parameters.init(c.config(), 17));
            for (int batch : new int[] {1, 4}) {
                MctsOptions options = WebNetCore.defaultOptions().withSims(40).withBatch(batch);
                WebNetCore core = new WebNetCore();
                core.create(c.jump(), c.players(), 40, batch, 5, "");
                core.setWeightsInfo(c.config().planes().id(), c.config().players());
                Mcts reference = new Mcts(network, options, 5);
                Position position = Position.initial(RuleSet.defaults(c.players()).withJumpRule(c.jump()));
                int[] history = new int[0];
                for (int ply = 0; ply < 6; ply++) {
                    MctsResult expected = reference.search(position);
                    int n = core.begin(history);
                    while (n > 0) {
                        n = forwardAndApply(core, network, n)[0];
                    }
                    WebNetCore.Best actual = core.best();
                    String what = c.name() + " batch " + batch + " ply " + ply;
                    assertEquals(expected.move(), actual.move(), what);
                    assertArrayEquals(expected.visits(), actual.visits(), what + " visits");
                    assertEquals(expected.nodes(), actual.nodes(), what + " nodes");
                    assertEquals(expected.sims(), actual.sims(), what + " sims");
                    assertFalse(actual.raceMove());
                    position.make(expected.move());
                    history = java.util.Arrays.copyOf(history, history.length + 1);
                    history[history.length - 1] = expected.move();
                }
            }
        }
    }

    /**
     * Under open foreign corners the search follows the move list of that rule: every chosen move is legal under OPEN,
     * and the facade's legal moves and outcome take the rule of the call.
     */
    @Test
    void playsLegalMovesWithOpenForeignCorners() {
        for (Case c : CASES) {
            Network network = new Network(Parameters.init(c.config(), 17));
            WebNetCore core = new WebNetCore();
            core.create(c.jump(), c.players(), RuleSet.ForeignCamps.OPEN, 30, 1, 5, "");
            core.setWeightsInfo(c.config().planes().id(), c.config().players());
            Position position = Position.initial(RuleSet.defaults(c.players()).withJumpRule(c.jump())
                    .withForeignCamps(RuleSet.ForeignCamps.OPEN));
            int[] history = new int[0];
            for (int ply = 0; ply < 30 && position.outcome() == null; ply++) {
                int[] legal = position.legalMoves();
                assertArrayEquals(legal, core.legalMoves(history, c.jump(), c.players(), RuleSet.ForeignCamps.OPEN));
                int n = core.begin(history);
                while (n > 0) {
                    n = forwardAndApply(core, network, n)[0];
                }
                int move = core.best().move();
                assertTrue(java.util.Arrays.stream(legal).anyMatch(m -> m == move), c.name() + " ply " + ply);
                position.make(move);
                history = java.util.Arrays.copyOf(history, history.length + 1);
                history[history.length - 1] = move;
            }
            assertEquals(null, core.outcome(history, c.jump(), c.players(), RuleSet.ForeignCamps.OPEN));
        }
    }

    @Test
    void goldenFilesAreTheCurrentJvmGames() throws Exception {
        Path dir = Path.of("test", "golden");
        for (GoldenGame.Variant v : GoldenGame.VARIANTS) {
            Path file = dir.resolve(v.name() + ".txt");
            assertTrue(Files.isRegularFile(file), file + " is missing: run :teavm-net:goldenExport");
            assertEquals(Files.readAllLines(file), GoldenGame.play(v), file + " is stale: run :teavm-net:goldenExport");
        }
    }

    private static WebNetCore ready() {
        WebNetCore core = new WebNetCore();
        core.create(RuleSet.JumpRule.ADJACENT, 2, 10, 2, 1, "");
        core.setWeightsInfo("classic1", 2);
        return core;
    }

    @Test
    void protocolErrorsCarryTheirCodes() {
        WebNetCore fresh = new WebNetCore();
        assertEquals("no-weights-info", assertThrows(NetFailure.class, () -> fresh.begin(new int[0])).code());
        WebNetCore core = ready();
        assertEquals("not-ready", assertThrows(NetFailure.class, core::inputs).code());
        assertEquals("not-ready", assertThrows(NetFailure.class, core::best).code());
        assertEquals("not-ready", assertThrows(NetFailure.class,
                () -> core.apply(new float[121], new float[121], new float[2])).code());
        assertEquals(1, core.begin(new int[0]));
        assertEquals(1, core.prepare(), "prepare is idempotent");
        assertEquals("bad-outputs", assertThrows(NetFailure.class,
                () -> core.apply(new float[120], new float[121], new float[2])).code());
        assertEquals("illegal-history", assertThrows(NetFailure.class, () -> core.begin(new int[] {-5})).code());
        assertEquals("bad-weights-info", assertThrows(NetFailure.class, () -> core.setWeightsInfo("nope", 2)).code());
        assertEquals("bad-weights-info", assertThrows(NetFailure.class, () -> core.setWeightsInfo("classic1v", 2)).code());
        assertEquals("bad-options", assertThrows(NetFailure.class,
                () -> core.create(RuleSet.JumpRule.ADJACENT, 2, 10, 1, 1, "cpuct=abc")).code());
        assertEquals("bad-options", assertThrows(NetFailure.class,
                () -> core.create(RuleSet.JumpRule.ADJACENT, 2, 10, 1, 1, "nonsense=1")).code());
        assertEquals("bad-options", assertThrows(NetFailure.class,
                () -> core.create(RuleSet.JumpRule.ADJACENT, 2, 10, 99, 1, "")).code(), "batch above the maximum");
    }

    @Test
    void s1AndRaceNeedTheTable() {
        WebNetCore core = ready();
        core.create(RuleSet.JumpRule.ADJACENT, 2, 10, 1, 1, "s1=1");
        assertEquals("no-table", assertThrows(NetFailure.class, () -> core.begin(new int[0])).code());
        core.create(RuleSet.JumpRule.ADJACENT, 2, 10, 1, 1, "race=2");
        assertEquals("no-table", assertThrows(NetFailure.class, () -> core.begin(new int[0])).code());
        assertEquals("table-magic", assertThrows(NetFailure.class, () -> core.setTable(new byte[100])).code());
    }

    @Test
    void networkOfTheWrongPlayerCountIsReportedNotSilentlyUsed() {
        WebNetCore core = new WebNetCore();
        core.create(RuleSet.JumpRule.ADJACENT, 3, 10, 1, 1, "");
        core.setWeightsInfo("classic1", 2); // a two-player net for a three-player game
        NetFailure e = assertThrows(NetFailure.class, () -> core.begin(new int[0]));
        assertEquals("bad-weights-info", e.code());
    }

    @Test
    void nonFiniteOutputsAreReportedAndTheSearchIsDropped() {
        WebNetCore core = ready();
        assertEquals(1, core.begin(new int[0]));
        float[] from = new float[121];
        java.util.Arrays.fill(from, Float.NaN);
        assertEquals("non-finite", assertThrows(NetFailure.class,
                () -> core.apply(from, new float[121], new float[2])).code());
        assertEquals("not-ready", assertThrows(NetFailure.class, core::inputs).code());
        assertEquals(1, core.begin(new int[0]), "a new move starts cleanly");
    }

    @Test
    void aGameEndsWithAnOutcomeAndNoMoreMoves() {
        // Random play to the end with one simulation per move, hash outputs: the facade's own legal moves and outcome.
        WebNetCore core = new WebNetCore();
        core.create(RuleSet.JumpRule.ADJACENT, 2, 2, 1, 3, "");
        core.setWeightsInfo("classic1", 2);
        int inputSize = core.inputSize();
        int[] history = new int[0];
        int plies = 0;
        while (core.outcome(history, RuleSet.JumpRule.ADJACENT, 2) == null && plies < 400) {
            int n = core.begin(history);
            while (n > 0) {
                float[][] out = HashBackend.forward(core.inputs(), inputSize, n, 2);
                n = core.apply(out[0], out[1], out[2]);
            }
            int move = core.best().move();
            assertTrue(contains(core.legalMoves(history, RuleSet.JumpRule.ADJACENT, 2), move));
            history = java.util.Arrays.copyOf(history, history.length + 1);
            history[history.length - 1] = move;
            plies++;
        }
        assertTrue(plies > 20);
        int[] finalHistory = history;
        if (core.outcome(finalHistory, RuleSet.JumpRule.ADJACENT, 2) != null) {
            assertEquals(0, core.legalMoves(finalHistory, RuleSet.JumpRule.ADJACENT, 2).length);
            assertEquals("illegal-history", assertThrows(NetFailure.class, () -> core.begin(finalHistory)).code());
        }
    }

    /** best() carries nine fields; the last is the root value of each move, in the order of the moves, as Double.toString. */
    @Test
    void facadeBestAppendsTheRootValuesInTheOrderOfTheMoves() {
        WebNetCore core = new WebNetCore();
        core.create(RuleSet.JumpRule.ADJACENT, 2, 12, 1, 3, "");
        core.setWeightsInfo("classic1", 2);
        assertEquals("ok", WebNet.create("adjacent", "2", 12, 1, 3, "", "closed", "4"));
        assertTrue(WebNet.setWeightsInfo("classic1", 2).startsWith("ok "));
        int[] history = new int[] {};
        int n = core.begin(history);
        while (n > 0) {
            float[][] out = HashBackend.forward(core.inputs(), core.inputSize(), n, 2);
            n = core.apply(out[0], out[1], out[2]);
        }
        WebNetCore.Best expected = core.best();
        String begin = WebNet.begin("");
        assertEquals("ok " + n0(begin), begin);
        int m = Integer.parseInt(begin.substring(3));
        while (m > 0) {
            float[][] out = HashBackend.forward(WebNet.inputs(), core.inputSize(), m, 2);
            m = Integer.parseInt(WebNet.apply(out[0], out[1], out[2]).substring(3));
        }
        String[] f = WebNet.best().split(" ");
        assertEquals(9, f.length - 1, "ok plus nine fields");
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < expected.values().length; i++) {
            values.append(i == 0 ? "" : ",").append(expected.values()[i]);
        }
        assertEquals(expected.moves().length, f[7].split(",").length);
        assertEquals(values.toString(), f[9]);
        assertTrue(expected.values().length > 1);
    }

    private static String n0(String reply) {
        return reply.substring(3);
    }

    private static boolean contains(int[] moves, int move) {
        for (int m : moves) {
            if (m == move) {
                return true;
            }
        }
        return false;
    }

    /**
     * A net game on 5 rows needs a network whose metadata key triangle_rows lists 5 (plan "neural networks for the 5-row
     * board", section 7.3). A network without the key is a 4-row network: setWeightsInfo then fails with {@code
     * unsupported}, the code the page maps to a text, and no search starts. The same holds the other way round.
     */
    @Test
    void aFiveRowGameNeedsANetworkThatListsFiveRows() {
        WebNetCore core = new WebNetCore();
        core.create(RuleSet.JumpRule.ADJACENT, 2, RuleSet.ForeignCamps.CLOSED, RuleSet.TriangleRows.FIVE, 8, 1, 1, "");
        NetFailure failure = assertThrows(NetFailure.class, () -> core.setWeightsInfo("classic1", 2));
        assertEquals("unsupported", failure.code());
        assertTrue(failure.getMessage().contains("5-row") && failure.getMessage().contains("triangle_rows=4"),
                failure.getMessage());
        assertEquals("no-weights-info", assertThrows(NetFailure.class, () -> core.begin(new int[0])).code(),
                "the refused weights info was not taken, so no search can start");

        assertEquals("ok", WebNet.create("adjacent", "2", 8, 1, 1, "", "closed", "5"));
        assertTrue(WebNet.setWeightsInfo("classic1", 2).startsWith("error unsupported "));
        assertEquals("ok " + PlaneSet.CLASSIC1.inputSize() + " 2", WebNet.setWeightsInfoRows("classic1", 2, "5"));
        assertEquals("ok " + PlaneSet.CLASSIC1.inputSize() + " 2", WebNet.setWeightsInfoRows("classic1", 2, "4,5"));
        assertTrue(WebNet.setWeightsInfoRows("classic1", 2, "5,4").startsWith("error bad-weights-info "));
        assertTrue(WebNet.create("adjacent", "2", 8, 1, 1, "", "closed", "7").startsWith("error internal "));

        // a 5-only network refuses a 4-row game, also when the game changes after the weights info
        assertEquals("ok", WebNet.create("adjacent", "2", 8, 1, 1, "", "closed", "4"));
        assertTrue(WebNet.setWeightsInfoRows("classic1", 2, "5").startsWith("error unsupported "));
        assertTrue(WebNet.setWeightsInfoRows("classic1", 2, "").startsWith("ok "), "empty means 4");
        WebNetCore switched = new WebNetCore();
        switched.create(RuleSet.JumpRule.ADJACENT, 2, RuleSet.ForeignCamps.CLOSED, RuleSet.TriangleRows.FIVE, 8, 1, 1, "");
        switched.setWeightsInfo("classic1", 2, "5");
        switched.create(RuleSet.JumpRule.ADJACENT, 2, RuleSet.ForeignCamps.CLOSED, RuleSet.TriangleRows.FOUR, 8, 1, 1, "");
        assertEquals("unsupported", assertThrows(NetFailure.class, () -> switched.begin(new int[0])).code());
    }

    /** With a 5-row network the facade searches 5-row positions like the synchronous search, inputs included. */
    @Test
    void playsLikeTheSynchronousSearchOnFiveRows() {
        NetworkConfig config = NetworkConfig.tiny(2).withPlanes(PlaneSet.CLASSIC1)
                .withRows(java.util.EnumSet.of(RuleSet.TriangleRows.FIVE));
        Network network = new Network(Parameters.init(config, 17));
        WebNetCore core = new WebNetCore();
        core.create(RuleSet.JumpRule.ADJACENT, 2, RuleSet.ForeignCamps.CLOSED, RuleSet.TriangleRows.FIVE, 40, 1, 5, "");
        core.setWeightsInfo("classic1", 2, "5");
        Mcts reference = new Mcts(network, WebNetCore.defaultOptions().withSims(40).withBatch(1), 5);
        Position position = Position.initial(RuleSet.defaults(2, RuleSet.TriangleRows.FIVE));
        int[] history = new int[0];
        for (int ply = 0; ply < 6; ply++) {
            MctsResult expected = reference.search(position);
            int n = core.begin(history);
            float[] first = core.inputs();
            float[] encoded = new float[PlaneSet.CLASSIC1.inputSize()];
            Encoder.encode(PlaneSet.CLASSIC1, position, encoded, 0);
            assertArrayEquals(encoded, java.util.Arrays.copyOf(first, encoded.length), "root input, ply " + ply);
            while (n > 0) {
                n = forwardAndApply(core, network, n)[0];
            }
            WebNetCore.Best actual = core.best();
            assertEquals(expected.move(), actual.move(), "ply " + ply);
            assertArrayEquals(expected.visits(), actual.visits(), "ply " + ply + " visits");
            position.make(expected.move());
            history = java.util.Arrays.copyOf(history, history.length + 1);
            history[history.length - 1] = expected.move();
        }
    }

    /** Rules-only calls need no net: legal moves and the outcome follow the board size. */
    @Test
    void legalMovesAndOutcomeFollowTheBoardSize() {
        for (int players : new int[] {2, 3}) {
            String p = String.valueOf(players);
            int[] five = Position.initial(RuleSet.defaults(players, RuleSet.TriangleRows.FIVE)).legalMoves();
            int[] four = Position.initial(RuleSet.defaults(players)).legalMoves();
            assertEquals("ok " + java.util.Arrays.stream(five).mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                    WebNet.legalMoves("", "adjacent", p, "closed", "5"));
            assertEquals("ok " + java.util.Arrays.stream(four).mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                    WebNet.legalMoves("", "adjacent", p, "closed", ""));
            assertEquals("ok ongoing", WebNet.outcome("", "adjacent", p, "closed", "5"));
        }
        assertTrue(WebNet.legalMoves("", "adjacent", "2", "closed", "5").length()
                != WebNet.legalMoves("", "adjacent", "2", "closed", "4").length());
    }
}
