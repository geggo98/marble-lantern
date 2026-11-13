package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sternhalma.selfplay.LoopFixture.NEVER;
import static sternhalma.selfplay.LoopFixture.gen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sternhalma.nn.Parameters;
import sternhalma.nn.Tensor;
import sternhalma.selfplay.LoopFixture.Result;

/**
 * The sweep tool on a TINY loop run: the variant that is the loop's own setup reproduces the loop's candidate to the
 * bit (so the tool is faithful), another schedule gives another network, the gate is played and summarised, and a
 * second run skips what exists.
 */
class SweepTest {

    @TempDir
    Path tmp;

    private static Result sweep(Path loop, Path out, String... more) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("--loop", loop.toString(), "--out", out.toString(), "--generation", "1",
                "--trainer", "cpu", "--pairs", "2", "--eval-samples", "40", "--threads", "2"));
        args.addAll(List.of(more));
        int code = Sweep.run(args.toArray(String[]::new), new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Result(code, e.toString(StandardCharsets.UTF_8), o.toString(StandardCharsets.UTF_8));
    }

    private Path tinyLoop() throws Exception {
        Path init = LoopFixture.initNetwork(tmp.resolve("init.safetensors"), 3);
        Path run = tmp.resolve("run");
        List<String> options = new ArrayList<>(List.of("--init", init.toString()));
        options.addAll(LoopFixture.options("--generations", "1"));
        Result loop = LoopFixture.loop(run, NEVER, options);
        assertEquals(0, loop.code(), loop.err());
        return run;
    }

    private static LoopConfig config(Path run) throws IOException {
        return LoopConfig.fromJson(RunMonitor.parse(Files.readString(run.resolve("run.json"))));
    }

    @Test
    void aTinyVariantReproducesTheLoopAndAnotherScheduleDiffers() throws Exception {
        Path run = tinyLoop();
        Path out = tmp.resolve("sweep");
        Result r = sweep(run, out, "--variants", "BASE,tiny:final=1e-5/cv=2");
        assertEquals(0, r.code(), r.err());

        Parameters loopCandidate = Parameters.load(gen(run, 1).resolve("candidate.safetensors"));
        Parameters base = Parameters.load(out.resolve("BASE").resolve("candidate.safetensors"));
        Parameters other = Parameters.load(out.resolve("tiny").resolve("candidate.safetensors"));
        boolean differs = false;
        for (Map.Entry<String, Tensor> e : loopCandidate.tensors().entrySet()) {
            assertArrayEquals(e.getValue().data(), base.get(e.getKey()).data(), "BASE reproduces " + e.getKey());
            differs |= !Arrays.equals(e.getValue().data(), other.get(e.getKey()).data());
        }
        assertTrue(differs, "a decaying schedule and another value coefficient give another network");

        assertEquals(1, LoopFixture.count(out.resolve("BASE").resolve("gate"), "vs-best-.*\\.jsonl"));
        assertTrue(r.out().contains("proxies BASE") && r.out().contains("gate BASE"), r.out());
        List<String> rows = Files.readAllLines(out.resolve("results.jsonl"));
        assertEquals(2, rows.size());
        assertTrue(rows.get(1).contains("\"variant\":\"tiny\""), rows.get(1));
        assertFalse(Files.exists(out.resolve("BASE").resolve("train")), "the training checkpoints are removed");

        Result again = sweep(run, out, "--variants", "BASE");
        assertEquals(0, again.code(), again.err());
        assertTrue(again.err().contains("candidate exists, skipped"), again.err());
        assertTrue(again.err().contains("0 ok, 1 skip"), again.err());
    }

    @Test
    void variantsAndOptionsAreChecked() throws Exception {
        Path run = tinyLoop();
        assertEquals(Sweep.EXIT_USAGE, sweep(run, tmp.resolve("o1"), "--variants", "nonsense").code());
        assertEquals(Sweep.EXIT_USAGE, sweep(run, tmp.resolve("o2"), "--variants", "x:bogus=1").code());
        assertEquals(Sweep.EXIT_USAGE, Sweep.run(new String[] {"--loop", run.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        LoopConfig cfg = config(run);
        assertEquals(15000, Sweep.Variant.parse("G", cfg).stepsFor(100));
        assertEquals(100, Sweep.Variant.parse("A", cfg).stepsFor(100));
        assertEquals(1e-4, Sweep.Variant.parse("A", cfg).lr());
        assertEquals(2, Sweep.Variant.parse("H", cfg).cv());
        assertThrows(IllegalArgumentException.class, () -> Sweep.Variant.parse("x:lr=0", cfg));
    }
}
