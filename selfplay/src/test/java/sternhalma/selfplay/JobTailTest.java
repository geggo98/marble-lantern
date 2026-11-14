package sternhalma.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tail of a {@link Job} (docs/training.md, "As implemented (block G2)"): the last items of the to-do list run in their own
 * pool with their own workers, from the start; which items they are depends on the to-do list and on nothing else.
 */
class JobTailTest {

    /** A job whose items return the name of the kind of worker that ran them and record the thread. */
    private static final class Probe extends Job<String> {

        final int tail;
        final Map<Integer, String> threads = new ConcurrentHashMap<>();

        Probe(Path dir, int items, int shard, int tail) {
            super(dir, "probe", items, shard, 2, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            this.tail = tail;
            this.progressSeconds = 0;
        }

        @Override
        int tailItems() {
            return tail;
        }

        @Override
        ExecutorService newTailPool() {
            if (tail == 0) {
                throw new AssertionError("no tail, no tail pool");
            }
            return Executors.newFixedThreadPool(2, Thread.ofPlatform().name("tail-probe-", 0).factory());
        }

        @Override
        Path shardFile(int shard) {
            return dir.resolve(String.format(Locale.ROOT, "probe-%03d.txt", shard));
        }

        @Override
        ShardState check(Path file, int shard, int first, int count) {
            try {
                return Files.readAllLines(file).size() == count ? ShardState.COMPLETE : ShardState.CORRUPT;
            } catch (IOException e) {
                return ShardState.CORRUPT;
            }
        }

        private Supplier<Worker<String>> worker(String kind) {
            return () -> item -> {
                threads.put(item, Thread.currentThread().getName());
                return item + ":" + kind;
            };
        }

        @Override
        Supplier<Worker<String>> workers() {
            return worker("main");
        }

        @Override
        Supplier<Worker<String>> tailWorkers() {
            return worker("tail");
        }

        @Override
        void write(Path file, int shard, int first, List<String> results) throws IOException {
            Files.write(file, results);
        }

        String kinds() throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int sh = 0; sh < shards(); sh++) {
                for (String line : Files.readAllLines(shardFile(sh))) {
                    sb.append(line).append(' ');
                }
            }
            return sb.toString().strip();
        }
    }

    @Test
    void theLastItemsOfTheToDoListRunInTheTailPoolWithTheTailWorkers(@TempDir Path tmp) throws Exception {
        Probe job = new Probe(tmp, 10, 3, 4);
        assertEquals(0, job.run());
        assertEquals("0:main 1:main 2:main 3:main 4:main 5:main 6:tail 7:tail 8:tail 9:tail", job.kinds());
        for (int i = 0; i < 10; i++) {
            assertEquals(i >= 6, job.threads.get(i).startsWith("tail-probe-"), "item " + i + " ran in " + job.threads.get(i));
        }
    }

    /** After a restart the tail is the end of what is left, not the end of the whole range. */
    @Test
    void theTailIsTheEndOfTheToDoListOfAResume(@TempDir Path tmp) throws Exception {
        assertEquals(0, new Probe(tmp, 10, 3, 0).run());
        Files.delete(tmp.resolve("probe-001.txt")); // items 3 to 5
        Files.delete(tmp.resolve("probe-003.txt")); // item 9
        Probe job = new Probe(tmp, 10, 3, 2);
        assertEquals(0, job.run());
        assertEquals(Map.of(3, "main", 4, "main", 5, "tail", 9, "tail").keySet(), job.threads.keySet());
        assertTrue(job.threads.get(5).startsWith("tail-probe-") && job.threads.get(9).startsWith("tail-probe-"));
        assertTrue(!job.threads.get(3).startsWith("tail-probe-") && !job.threads.get(4).startsWith("tail-probe-"));
        assertEquals("0:main 1:main 2:main 3:main 4:main 5:tail 6:main 7:main 8:main 9:tail", job.kinds());
    }

    /** A tail larger than what is left takes everything; the main pool has nothing to do. */
    @Test
    void aTailLargerThanTheToDoListTakesEverything(@TempDir Path tmp) throws Exception {
        Probe job = new Probe(tmp, 5, 2, 100);
        assertEquals(0, job.run());
        assertEquals("0:tail 1:tail 2:tail 3:tail 4:tail", job.kinds());
    }

    @Test
    void withoutATailThereIsOnePoolAndNoTailPool(@TempDir Path tmp) throws Exception {
        Probe job = new Probe(tmp, 5, 2, 0);
        assertEquals(0, job.run());
        assertEquals("0:main 1:main 2:main 3:main 4:main", job.kinds());
    }
}
