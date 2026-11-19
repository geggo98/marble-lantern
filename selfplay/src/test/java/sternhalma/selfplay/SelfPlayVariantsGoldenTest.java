package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.search.Strategies;

/**
 * Regression: the shards of the two-player adjacent tool are the same bytes as before the variant options existed.
 * The expected digests were taken from the code of commit 28363f3 (before {@code --players} and {@code --jump}).
 * Everything but the CPU-time tensor {@code game_cpu_us} (it differs from run to run) enters the digest.
 */
class SelfPlayVariantsGoldenTest {

    private static final String SPEC = Strategies.BEST_R1_SPEC + ",nodes=300,tt=14";

    static String digest(Path file) throws Exception {
        SampleFile.Contents c = SampleFile.read(file);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, String> e : new TreeMap<>(c.metadata()).entrySet()) {
            md.update((e.getKey() + "=" + e.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        List<String> names = new ArrayList<>(new TreeMap<>(c.tensors()).keySet());
        for (String n : names) {
            if (n.equals("game_cpu_us")) {
                continue;
            }
            SampleFile.Tensor t = c.tensor(n);
            md.update((n + ":" + t.dtype() + ":" + java.util.Arrays.toString(t.shape()) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            md.update(t.data());
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static Path shard(Path dir, SelfPlayGame.Settings settings) throws Exception {
        SelfPlayGame game = new SelfPlayGame(settings);
        List<GameRecord> games = new ArrayList<>();
        for (int id = 0; id < 4; id++) {
            games.add(game.play(id));
        }
        Path file = dir.resolve("golden.safetensors");
        GameShard.write(file, settings, 0, 0, games);
        return file;
    }

    @Test
    void bootstrapShardIsByteIdenticalToBeforeTheVariantOptions(@TempDir Path dir) throws Exception {
        SelfPlayGame.Settings s = new SelfPlayGame.Settings(SPEC, 1006, Adjudication.Config.PRODUCTION, 0.1,
                OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, 0.05, true, true);
        assertEquals("96a2b87ea85c9cf2757ba042e285f05c1f564567c031daef3a1cf39e3c7e5ae2", digest(shard(dir, s)));
    }

    @Test
    void teacherShardIsByteIdenticalToBeforeTheVariantOptions(@TempDir Path dir) throws Exception {
        SelfPlayGame.Settings s = new SelfPlayGame.Settings(SPEC, 1007, new Adjudication.Config(true, 500, 2, 0, 4),
                0.1, OpeningSampler.Mode.UNIFORM, 4, 10, 30, 1, 0.05, true, true, new Teacher.Config(2, 0.06));
        assertEquals("61b669e8daf2e3b35218d4dd8fc1aa7b5bd3fd2b29691cba76d9255160f0e0f3", digest(shard(dir, s)));
    }
}
