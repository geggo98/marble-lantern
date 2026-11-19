package sternhalma.nn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sternhalma.engine.Layout;
import sternhalma.engine.Move;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;
import sternhalma.engine.RuleSet.JumpRule;

/**
 * The plane set {@code classic1v} and format version 3 (docs/network.md, "Format version 3 (variants)"): planes,
 * expander, metadata and files, evaluator and priors for the four variants (two and three players, adjacent and long
 * jumps).
 */
class VariantPlanesTest {

    private static final int[] PLAYER_COUNTS = {2, 3};
    private static final JumpRule[] JUMPS = {JumpRule.ADJACENT, JumpRule.LONG};

    @TempDir
    Path dir;

    private static List<Position> positions(int players, JumpRule jump) {
        List<Position> all = Playouts.positions(players, jump, 3, 160, 31L * players + jump.ordinal());
        List<Position> picked = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 3) {
            picked.add(all.get(i));
        }
        return picked;
    }

    private static float[] encode(PlaneSet planes, Position position) {
        float[] out = new float[planes.inputSize()];
        Encoder.encode(planes, position, out, 0);
        return out;
    }

    // ---- planes ----

    @Test
    void expanderEqualsEncoderForEveryMoverInAllFourVariants() {
        for (int players : PLAYER_COUNTS) {
            for (JumpRule jump : JUMPS) {
                Set<Integer> movers = new HashSet<>();
                List<Position> ps = positions(players, jump);
                assertTrue(ps.size() > 100, "enough positions");
                for (Position p : ps) {
                    movers.add(p.sideToMove());
                    float[] expanded = new float[Encoder.VARIANT_PLANES * HexGrid.ROWS];
                    SampleExpander.expand(PlaneSet.CLASSIC1V, players, jump, SampleExpanderTest.cellsOf(p), 0, p.ply(),
                            p.rules().moveLimit(), false, expanded, 0);
                    assertArrayEquals(encode(PlaneSet.CLASSIC1V, p), expanded,
                            players + " players " + jump + " ply " + p.ply());
                }
                assertEquals(players, movers.size(), "every mover occurs");
            }
        }
    }

    @Test
    void theMirroredExpansionIsTheMirrorOfTheEncodingForTwoPlayers() {
        for (JumpRule jump : JUMPS) {
            for (Position p : positions(2, jump)) {
                float[] plain = encode(PlaneSet.CLASSIC1V, p);
                float[] expanded = new float[plain.length];
                SampleExpander.expand(PlaneSet.CLASSIC1V, 2, jump, SampleExpanderTest.cellsOf(p), 0, p.ply(),
                        p.rules().moveLimit(), true, expanded, 0);
                float[] mirrored = new float[plain.length];
                for (int c = 0; c < HexGrid.CELLS; c++) {
                    System.arraycopy(plain, Encoder.index(Encoder.VARIANT_PLANES, c, 0), mirrored,
                            Encoder.index(Encoder.VARIANT_PLANES, SampleExpander.mirrorCell(c), 0),
                            Encoder.VARIANT_PLANES);
                }
                // The reach planes of the mirrored position equal the mirrored reach planes (the board is symmetric).
                assertArrayEquals(mirrored, expanded, jump + " ply " + p.ply());
            }
        }
    }

    @Test
    void theJumpRuleOfTheExpanderMattersForTheReachPlanes() {
        // Guards the test above: with the wrong jump rule the planes differ, so the equality is not vacuous.
        boolean differs = false;
        for (Position p : positions(2, JumpRule.LONG)) {
            float[] expanded = new float[Encoder.VARIANT_PLANES * HexGrid.ROWS];
            SampleExpander.expand(PlaneSet.CLASSIC1V, 2, JumpRule.ADJACENT, SampleExpanderTest.cellsOf(p), 0, p.ply(),
                    p.rules().moveLimit(), false, expanded, 0);
            differs |= !Arrays.equals(encode(PlaneSet.CLASSIC1V, p), expanded);
        }
        assertTrue(differs);
    }

    @Test
    void theVariantPlanesAreConstantsOfTheRulesAndThePaddingRowStaysZero() {
        for (int players : PLAYER_COUNTS) {
            for (JumpRule jump : JUMPS) {
                for (Position p : positions(players, jump)) {
                    float[] in = encode(PlaneSet.CLASSIC1V, p);
                    for (int row = 0; row < HexGrid.ROWS; row++) {
                        boolean padding = row == HexGrid.PADDING;
                        assertEquals(padding ? 0f : jump == JumpRule.LONG ? 1f : 0f,
                                in[Encoder.index(Encoder.VARIANT_PLANES, row, Encoder.LONG)], "long, row " + row);
                        assertEquals(padding ? 0f : players == 3 ? 1f : 0f,
                                in[Encoder.index(Encoder.VARIANT_PLANES, row, Encoder.THREE_PLAYERS)],
                                "three, row " + row);
                    }
                }
            }
        }
    }

    @Test
    void theFirstThirteenPlanesAreThoseOfClassic1ForAdjacentJumps() {
        for (int players : PLAYER_COUNTS) {
            for (Position p : positions(players, JumpRule.ADJACENT)) {
                float[] classic = encode(PlaneSet.CLASSIC1, p);
                float[] variants = encode(PlaneSet.CLASSIC1V, p);
                for (int row = 0; row < HexGrid.ROWS; row++) {
                    for (int plane = 0; plane < Encoder.CLASSIC_PLANES; plane++) {
                        assertEquals(classic[Encoder.index(Encoder.CLASSIC_PLANES, row, plane)],
                                variants[Encoder.index(Encoder.VARIANT_PLANES, row, plane)],
                                "row " + row + " plane " + plane);
                    }
                }
            }
        }
    }

    @Test
    void theEvalPlaneIsZeroForLongAndForThreePlayersButNotForTwoPlayerAdjacent() {
        boolean adjacentNonZero = false;
        boolean classic1LongNonZero = false;
        for (int players : PLAYER_COUNTS) {
            for (JumpRule jump : JUMPS) {
                for (Position p : positions(players, jump)) {
                    float eval = encode(PlaneSet.CLASSIC1V, p)[Encoder.index(Encoder.VARIANT_PLANES, 0, Encoder.EVAL)];
                    if (players == 3 || jump == JumpRule.LONG) {
                        assertEquals(0f, eval, "eval, " + players + " players " + jump);
                    } else {
                        adjacentNonZero |= eval != 0f;
                    }
                    if (players == 2 && jump == JumpRule.LONG) {
                        // classic1 is unchanged: it keeps writing the linear evaluation under LONG
                        classic1LongNonZero |= encode(PlaneSet.CLASSIC1, p)[Encoder.index(Encoder.CLASSIC_PLANES, 0,
                                Encoder.EVAL)] != 0f;
                    }
                }
            }
        }
        assertTrue(adjacentNonZero);
        assertTrue(classic1LongNonZero);
    }

    @Test
    void theReachPlanesFollowTheRulesOfThePosition() {
        for (int players : PLAYER_COUNTS) {
            for (JumpRule jump : JUMPS) {
                for (Position p : positions(players, jump)) {
                    Orientation o = Orientation.of(p);
                    float[] in = encode(PlaneSet.CLASSIC1V, p);
                    boolean[] ends = new boolean[HexGrid.CELLS];
                    for (int move : p.legalMoves()) {
                        ends[o.toCanonical(Move.to(move))] = true;
                    }
                    for (int c = 0; c < HexGrid.CELLS; c++) {
                        assertEquals(ends[c] ? 1f : 0f, in[Encoder.index(Encoder.VARIANT_PLANES, c, Encoder.REACH_OWN)],
                                "reach own, cell " + c + " " + jump);
                    }
                }
            }
        }
    }

    // ---- config, metadata, files ----

    private static Map<String, String> v3() {
        return new LinkedHashMap<>(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V).toMetadata());
    }

    @Test
    void aVariantConfigIsVersion3WithThreePlayersAndFifteenPlanes() {
        NetworkConfig config = NetworkConfig.small(2).withPlanes(PlaneSet.CLASSIC1V);
        assertEquals(3, config.players(), "withPlanes sets the value head width");
        assertEquals(15, config.inputPlanes());
        assertEquals(NetworkConfig.FORMAT_VERSION_VARIANTS, 3);
        Map<String, String> m = config.toMetadata();
        assertEquals(List.of("format", "format_version", "channels", "blocks", "players", "value_hidden",
                "input_planes", "planes"), List.copyOf(m.keySet()));
        assertEquals("3", m.get("format_version"));
        assertEquals("3", m.get("players"));
        assertEquals("15", m.get("input_planes"));
        assertEquals("classic1v", m.get("planes"));
        assertEquals(config, NetworkConfig.fromMetadata(m));
        assertTrue(config.planes().residualPriors());
        assertThrows(IllegalArgumentException.class,
                () -> new NetworkConfig(8, 1, 2, 32, PlaneSet.CLASSIC1V), "two value outputs do not serve three players");
    }

    @Test
    void parameterCountOfAVariantNetworkIsPinned() {
        // hand calculation for TINY (C = 8, B = 1, H = 32, P = 3, I = 15):
        // conv_in 7*15*8 + 8 = 848; block 2 * (7*8*8 + 8) = 912; policy 2*8 + 2 = 18;
        // value (32*8 + 32) + (3*32 + 3) = 387; priors 2
        assertEquals(848 + 912 + 18 + 387 + 2, NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V).parameterCount());
        assertEquals(2_167, NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V).parameterCount());
        assertEquals(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V).parameterCount(),
                Parameters.init(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V), 1).tensors().values().stream()
                        .mapToLong(t -> t.data().length).sum());
    }

    @Test
    void version3IsRejectedUnlessItIsExactlyClassic1vWithThreePlayersAndFifteenPlanes() {
        NetworkConfig.fromMetadata(v3());
        for (String[] change : new String[][] {
            {"planes", "classic1"}, {"planes", "plain"}, {"planes", "classic1-planes"}, {"planes", "classic2"},
            {"players", "2"}, {"input_planes", "13"}, {"input_planes", "7"}, {"format_version", "4"},
            {"format_version", "2"}, {"format_version", "1"}}) {
            Map<String, String> m = v3();
            m.put(change[0], change[1]);
            assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromMetadata(m), String.join("=", change));
        }
        Map<String, String> classic1AsV3 = new LinkedHashMap<>(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1).toMetadata());
        classic1AsV3.put("format_version", "3");
        assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromMetadata(classic1AsV3),
                "a consistent classic1 config is not a version 3 file");
        Map<String, String> noPlanes = v3();
        noPlanes.remove("planes");
        assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromMetadata(noPlanes));
    }

    @Test
    void aVersion2FileCannotNameTheVariantPlanes() {
        Map<String, String> m = v3();
        m.put("format_version", "2");
        assertThrows(IllegalArgumentException.class, () -> NetworkConfig.fromMetadata(m));
    }

    @Test
    void aVersion3FileRoundTripsAndAnOldReaderRejectsItAsAnUnknownVersion() throws IOException {
        Parameters p = Parameters.uniform(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V), 5, 0.3f);
        p.extraMetadata().put("step", "12");
        Path file = dir.resolve("v3.safetensors");
        p.save(file);
        Safetensors.Contents contents = Safetensors.read(file);
        assertEquals("3", contents.metadata().get("format_version"));
        Parameters loaded = Parameters.load(file);
        assertEquals(p.config(), loaded.config());
        assertEquals("12", loaded.extraMetadata().get("step"));
        assertEquals(List.copyOf(p.tensors().keySet()), List.copyOf(loaded.tensors().keySet()));
        for (String name : p.tensors().keySet()) {
            assertArrayEquals(p.get(name).data(), loaded.get(name).data(), name);
        }
        // A reader of formats 1 and 2 accepts only the versions 1 and 2 (it threw "unknown format_version" for the rest),
        // so the version 3 in the file is what makes it reject the file.
    }

    // ---- evaluator ----

    private static Network tinyVariantNetwork() {
        return new Network(Parameters.uniform(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V), 77, 0.4f));
    }

    @Test
    void aVariantNetworkPlaysLegalMovesInAllFourVariants() {
        Evaluator evaluator = new Evaluator(tinyVariantNetwork());
        for (int players : PLAYER_COUNTS) {
            for (JumpRule jump : JUMPS) {
                Position position = Position.initial(RuleSet.defaults(players).withMoveLimit(300).withJumpRule(jump));
                int plies = 0;
                while (plies < 60 && position.outcome() == null) {
                    Evaluation e = evaluator.evaluate(position);
                    assertArrayEquals(position.legalMoves(), e.moves());
                    assertEquals(players, e.values().length, "one value per player of the position");
                    int best = 0;
                    double sum = 0;
                    for (int i = 0; i < e.priors().length; i++) {
                        sum += e.priors()[i];
                        if (e.priors()[i] > e.priors()[best]) {
                            best = i;
                        }
                    }
                    assertEquals(1.0, sum, 1e-5);
                    for (float v : e.values()) {
                        assertTrue(Math.abs(v) <= 1f);
                    }
                    int chosen = e.moves()[best];
                    assertTrue(Arrays.stream(position.legalMoves()).anyMatch(m -> m == chosen), "legal move " + Move.toString(e.moves()[best]));
                    position.make(e.moves()[best]);
                    plies++;
                }
                assertEquals(60, plies, players + " players " + jump + " played 60 plies");
            }
        }
    }

    @Test
    void aVariantNetworkMapsTheFirstOutputsOfThePositionsPlayers() {
        Network network = tinyVariantNetwork();
        Evaluator evaluator = new Evaluator(network);
        for (int players : PLAYER_COUNTS) {
            for (Position p : positions(players, JumpRule.LONG).subList(0, 40)) {
                float[] input = encode(PlaneSet.CLASSIC1V, p);
                float[] policy = new float[Encoder.POLICY_SIZE];
                float[] values = new float[3];
                network.forward(input, 1, policy, values);
                Evaluation e = evaluator.evaluate(p);
                for (int a = 0; a < players; a++) {
                    assertEquals(values[Math.floorMod(a - p.sideToMove(), players)], e.values()[a], 0f,
                            "player " + a + " of " + players);
                }
            }
        }
    }

    @Test
    void aPositionBatchMayMixPlayerCountsAndJumpRules() {
        Evaluator evaluator = new Evaluator(tinyVariantNetwork());
        List<Position> mixed = new ArrayList<>();
        for (int players : PLAYER_COUNTS) {
            for (JumpRule jump : JUMPS) {
                mixed.addAll(positions(players, jump).subList(0, 5));
            }
        }
        List<Evaluation> together = evaluator.evaluate(mixed);
        for (int i = 0; i < mixed.size(); i++) {
            Evaluation alone = evaluator.evaluate(mixed.get(i));
            assertArrayEquals(alone.values(), together.get(i).values(), 1e-5f);
            assertArrayEquals(alone.priors(), together.get(i).priors(), 1e-5f);
        }
    }

    @Test
    void theOtherNetworksStillRejectAMismatchingPlayerCount() {
        Position two = Position.initial(RuleSet.defaults(2));
        Position three = Position.initial(RuleSet.defaults(3));
        for (PlaneSet planes : List.of(PlaneSet.PLAIN, PlaneSet.CLASSIC1, PlaneSet.CLASSIC1_PLANES)) {
            Evaluator two2 = new Evaluator(new Network(Parameters.uniform(NetworkConfig.tiny(2).withPlanes(planes), 1, 0.3f)));
            Evaluator three3 = new Evaluator(new Network(Parameters.uniform(NetworkConfig.tiny(3).withPlanes(planes), 1, 0.3f)));
            assertThrows(IllegalArgumentException.class, () -> two2.evaluate(three), planes.id());
            assertThrows(IllegalArgumentException.class, () -> three3.evaluate(two), planes.id());
            two2.evaluate(two);
            three3.evaluate(three);
        }
    }

    // ---- priors ----

    @Test
    void anUntrainedVariantNetworkIsTheDistancePriorAndHasNoValuePriorForLongAndThreePlayers() {
        Evaluator evaluator = new Evaluator(new Network(Parameters.init(NetworkConfig.tiny(3).withPlanes(PlaneSet.CLASSIC1V), 1)));
        for (int players : PLAYER_COUNTS) {
            for (JumpRule jump : JUMPS) {
                Layout layout = Layout.of(players);
                boolean valueOff = players == 3 || jump == JumpRule.LONG;
                boolean valueSeen = false;
                for (Position p : positions(players, jump).subList(0, 60)) {
                    Evaluation e = evaluator.evaluate(p);
                    int mover = p.sideToMove();
                    for (int i = 1; i < e.moves().length; i++) {
                        // policy prior on: the logit is the distance gain (beta = 1), so the log ratio of two priors is
                        // the difference of the gains, computed here from the layout
                        double gain0 = gain(layout, mover, e.moves()[0]);
                        double gaini = gain(layout, mover, e.moves()[i]);
                        double ratio = Math.log((double) e.priors()[i] / e.priors()[0]);
                        assertEquals(gaini - gain0, ratio, 2e-4, players + " players " + jump);
                    }
                    for (float v : e.values()) {
                        if (valueOff) {
                            assertEquals(0f, v, 0f, "value prior off, " + players + " players " + jump);
                        } else {
                            valueSeen |= v != 0f;
                        }
                    }
                }
                if (!valueOff) {
                    assertTrue(valueSeen, "value prior on for two players with adjacent jumps");
                }
            }
        }
        assertNotEquals(0, positions(2, JumpRule.LONG).size());
    }

    private static double gain(Layout layout, int mover, int move) {
        return layout.distance(mover, Move.from(move)) - layout.distance(mover, Move.to(move));
    }
}
