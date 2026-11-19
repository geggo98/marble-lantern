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

    private static boolean contains(int[] moves, int move) {
        for (int m : moves) {
            if (m == move) {
                return true;
            }
        }
        return false;
    }
}
