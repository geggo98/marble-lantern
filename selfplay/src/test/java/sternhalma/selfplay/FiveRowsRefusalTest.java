package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sternhalma.engine.RuleSet;

/** Self-play, training data and the replay buffer stay on the 4-row board; a 5-row shard is refused by name. */
class FiveRowsRefusalTest {

    @Test
    void shardRulesRoundTripFiveAndDefaultToFour() {
        for (int players : new int[] {2, 3}) {
            RuleSet five = RuleSet.defaults(players, RuleSet.TriangleRows.FIVE).withJumpRule(RuleSet.JumpRule.LONG);
            assertEquals(five, GameShard.rules(Map.of("players", "" + players, "rules", five.toString())));
            RuleSet four = RuleSet.defaults(players);
            // a rules text written before the switch has no triangleRows key
            assertEquals(RuleSet.TriangleRows.FOUR,
                    GameShard.rules(Map.of("players", "" + players, "rules", four.toString())).triangleRows());
            assertEquals(four, GameShard.rules(Map.of("players", "" + players)));
        }
    }

    @Test
    void shardTensorsRefuseFiveRows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GameShard.tensors(RuleSet.defaults(2, RuleSet.TriangleRows.FIVE), List.of()));
        assertTrue(e.getMessage().contains("triangleRows=FIVE"), e.getMessage());
    }

    @Test
    void settingsRulesAreFourRows() {
        for (int players : new int[] {2, 3}) {
            for (RuleSet.JumpRule jump : RuleSet.JumpRule.values()) {
                SelfPlayGame.Settings s = new SelfPlayGame.Settings("ab:nodes=10", 1, Adjudication.Config.OFF, 0,
                        OpeningSampler.Mode.UNIFORM, 4, 8, 1, 2, 0, false, false, null, players, jump);
                assertEquals(RuleSet.TriangleRows.FOUR, s.rules().triangleRows());
            }
        }
    }

    @Test
    void replayBufferRefusesFiveRowShard(@TempDir Path dir) throws IOException {
        for (int players : new int[] {2, 3}) {
            Path four = SyntheticShards.write(dir, "four" + players + ".safetensors", players, 7, 0, 2, 3, 5);
            SampleFile.Contents c = SampleFile.read(four);
            Map<String, String> meta = new HashMap<>(c.metadata());
            meta.put("rules", RuleSet.defaults(players, RuleSet.TriangleRows.FIVE).toString());
            Path five = dir.resolve("five" + players + ".safetensors");
            SampleFile.write(five, c.tensors(), meta);

            ReplayBuffer ok = new ReplayBuffer(players, 100, false);
            ReplayBuffer.Loaded good = ok.addGeneration(0, List.of(four));
            assertEquals(List.of(), good.skipped());
            assertEquals(6, good.samples());
            // the same samples with only the rules text changed: the file is skipped like a corrupt one, nothing loads
            ReplayBuffer b = new ReplayBuffer(players, 100, false);
            ReplayBuffer.Loaded bad = b.addGeneration(0, List.of(five));
            assertEquals(List.of(five), bad.skipped());
            assertEquals(0, bad.samples());
        }
    }
}
