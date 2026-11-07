package sternhalma.search;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import sternhalma.engine.Outcome;
import sternhalma.engine.Position;
import sternhalma.engine.RuleSet;

/**
 * Moves-only game files for refitting the linear weights (round-2 plan, E04): self-play games of one player spec
 * from the arena openings, stored as move lists, so that a later fit replays them and computes any features
 * without searching again.
 *
 * <p>Format (text, UTF-8): comment lines start with {@code #}; the header holds {@code spec=}, {@code seed=} and
 * {@code openingPlies=}. Every other line is one game: {@code <id> <value of player 0> <n> <move_1> ... <move_n>},
 * the moves packed as by {@link sternhalma.engine.Move} and including the opening plies, the value as by
 * {@link Outcome#value(int)} (1 win, -1 loss, distance score at the move limit).
 *
 * <p>Game {@code id} starts from {@code Arena.opening(rules, seed, id, openingPlies)}, the opening of arena pair
 * {@code id} with that seed. The player is deterministic, so one game per opening (no colour swap).
 *
 * <p>{@link #generate} writes shards of {@link #SHARD} games under temporary names and renames each when it is
 * complete, so a shard file is either complete or absent. A rerun skips complete shards (checked by the game ids
 * they hold), deletes leftover temporary files (CLEANUP) and holds a lock on {@code .games.lock} in the output
 * directory; a second run while the lock is held ends with exit code 75 and prints the holder from
 * {@code .games.status} (display only). Exit codes: 0 done or nothing to do, 1 error, 75 already running; on
 * Ctrl-C the JVM ends with 130 and at most the shards in flight are lost.
 */
final class GameFiles {

    static final int SHARD = 50;

    /** One stored game. */
    record Game(int id, double value0, int[] moves) {
    }

    private GameFiles() {
    }

    static String header(String spec, long seed, int openingPlies) {
        return "# sternhalma games v1\n# spec=" + spec + "\n# seed=" + seed + "\n# openingPlies=" + openingPlies + "\n";
    }

    static String line(Game g) {
        StringBuilder sb = new StringBuilder();
        sb.append(g.id()).append(' ').append(g.value0()).append(' ').append(g.moves().length);
        for (int m : g.moves()) {
            sb.append(' ').append(m);
        }
        return sb.append('\n').toString();
    }

    static Game parse(String line) {
        String[] t = line.trim().split(" ");
        int n = Integer.parseInt(t[2]);
        if (t.length != 3 + n) {
            throw new IllegalArgumentException("game line has " + (t.length - 3) + " moves, header says " + n);
        }
        int[] moves = new int[n];
        for (int i = 0; i < n; i++) {
            moves[i] = Integer.parseInt(t[3 + i]);
        }
        return new Game(Integer.parseInt(t[0]), Double.parseDouble(t[1]), moves);
    }

    static List<Game> read(Path file) throws IOException {
        List<Game> games = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank() && !line.startsWith("#")) {
                games.add(parse(line));
            }
        }
        return games;
    }

    /** Reads files or directories (all {@code *.txt} shards inside, in name order); ids must be distinct. */
    static List<Game> readAll(String... paths) throws IOException {
        List<Game> games = new ArrayList<>();
        for (String p : paths) {
            Path path = Path.of(p);
            if (Files.isDirectory(path)) {
                try (Stream<Path> s = Files.list(path)) {
                    for (Path f : s.filter(f -> f.getFileName().toString().endsWith(".txt")).sorted().toList()) {
                        games.addAll(read(f));
                    }
                }
            } else {
                games.addAll(read(path));
            }
        }
        if (games.stream().mapToInt(Game::id).distinct().count() != games.size()) {
            throw new IllegalArgumentException("duplicate game ids in " + Arrays.toString(paths));
        }
        return games;
    }

    /** A header value ({@code key=...}) of a game file, or null. */
    static String headerValue(Path file, String key) throws IOException {
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("# " + key + "=")) {
                return line.substring(key.length() + 3);
            }
        }
        return null;
    }

    /** Plays game {@code id}: self-play of {@code spec} from the arena opening; every move is checked. */
    static Game play(PlayerSpec spec, long seed, int id, int openingPlies) {
        RuleSet rules = RuleSet.defaults(2);
        List<Integer> moves = new ArrayList<>();
        Position pos = Position.initial(rules);
        for (int m : openingMoves(seed, id, openingPlies)) {
            moves.add(m);
            pos.make(m);
        }
        if (!pos.equals(Arena.opening(rules, seed, id, openingPlies))) {
            throw new IllegalStateException("opening replay differs from Arena.opening for game " + id);
        }
        AlphaBetaPlayer[] players = {spec.create(), spec.create()};
        Outcome outcome;
        while (true) {
            outcome = pos.outcome();
            if (outcome != null) {
                break;
            }
            int[] legal = pos.legalMoves();
            if (legal.length == 0) {
                outcome = pos.noMovesOutcome();
                break;
            }
            AlphaBetaPlayer player = players[pos.sideToMove()];
            int m = player.choose(pos);
            if (Arrays.stream(legal).noneMatch(x -> x == m)) {
                throw new IllegalMoveException(player.name(), m, pos);
            }
            moves.add(m);
            pos.make(m);
        }
        return new Game(id, outcome.value(0), moves.stream().mapToInt(Integer::intValue).toArray());
    }

    /** The random opening plies of {@code Arena.opening}, as moves. */
    static int[] openingMoves(long seed, int id, int plies) {
        java.util.Random random = new java.util.Random(seed * 1_000_003L + id);
        Position pos = Position.initial(RuleSet.defaults(2));
        int[] out = new int[plies];
        for (int i = 0; i < plies; i++) {
            int[] moves = pos.legalMoves();
            out[i] = moves[random.nextInt(moves.length)];
            pos.make(out[i]);
        }
        return out;
    }

    /** The lock, or null if another process (or another run in this JVM) holds it. */
    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    static Path shardFile(Path dir, long seed, int shard) {
        return dir.resolve(String.format(Locale.ROOT, "games-s%d-%04d.txt", seed, shard));
    }

    /** True if the shard file exists and holds exactly the ids of its shard (a complete, published shard). */
    static boolean complete(Path file, int shard, int games) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            int[] ids = read(file).stream().mapToInt(Game::id).sorted().toArray();
            int from = shard * SHARD;
            int to = Math.min(games, from + SHARD);
            if (ids.length != to - from) {
                return false;
            }
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] != from + i) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * {@code games <dir> <count> <seed> <threads> <spec>}: games 0..count-1, see the class comment. Returns the
     * exit code.
     */
    static int generate(Path dir, int count, long seed, int threads, String specText) throws Exception {
        Files.createDirectories(dir);
        PlayerSpec spec = PlayerSpec.parse(specText);
        int openingPlies = 4;
        Path status = dir.resolve(".games.status");
        try (FileChannel lockChannel = FileChannel.open(dir.resolve(".games.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = tryLock(lockChannel)) {
            if (lock == null) {
                String holder = Files.exists(status) ? Files.readString(status).trim() : "?";
                System.err.println("already running: " + holder);
                return 75;
            }
            Files.writeString(status, "pid=" + ProcessHandle.current().pid() + " start=" + Instant.now() + "\n");
            int cleanup = 0;
            try (Stream<Path> s = Files.list(dir)) {
                for (Path f : s.filter(f -> f.getFileName().toString().contains(".tmp")).toList()) {
                    Files.delete(f);
                    cleanup++;
                }
            }
            int shards = (count + SHARD - 1) / SHARD;
            List<Integer> todo = new ArrayList<>();
            int skip = 0;
            for (int sh = 0; sh < shards; sh++) {
                Path f = shardFile(dir, seed, sh);
                if (complete(f, sh, count)) {
                    String fileSpec = headerValue(f, "spec");
                    if (!specText.equals(fileSpec)) {
                        System.err.println("shard " + f + " was written by spec " + fileSpec + ", not " + specText);
                        return 1;
                    }
                    skip++;
                } else {
                    todo.add(sh);
                }
            }
            int todoGames = todo.stream().mapToInt(sh -> Math.min(count, sh * SHARD + SHARD) - sh * SHARD).sum();
            System.err.printf(Locale.ROOT, "%d shards: %d complete (skip), %d to play (%d games), %d tmp files "
                    + "removed (cleanup)%n", shards, skip, todo.size(), todoGames, cleanup);
            AtomicInteger played = new AtomicInteger();
            long t0 = System.nanoTime();
            long[] lastReport = {t0};
            int ok = 0;
            int err = 0;
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                List<List<Future<Game>>> perShard = new ArrayList<>();
                for (int sh : todo) {
                    List<Future<Game>> fs = new ArrayList<>();
                    for (int id = sh * SHARD; id < Math.min(count, sh * SHARD + SHARD); id++) {
                        int game = id;
                        fs.add(pool.submit(() -> {
                            Game g = play(spec, seed, game, openingPlies);
                            int n = played.incrementAndGet();
                            synchronized (lastReport) {
                                long now = System.nanoTime();
                                if (now - lastReport[0] >= 10_000_000_000L || n == todoGames) {
                                    lastReport[0] = now;
                                    double el = (now - t0) / 1e9;
                                    double rate = n / el;
                                    System.err.printf(Locale.ROOT, "%d/%d games  %.2f/s  ETA %.0fs  elapsed %.0fs%n",
                                            n, todoGames, rate, (todoGames - n) / rate, el);
                                }
                            }
                            return g;
                        }));
                    }
                    perShard.add(fs);
                }
                for (int i = 0; i < todo.size(); i++) {
                    Path f = shardFile(dir, seed, todo.get(i));
                    Path tmp = f.resolveSibling(f.getFileName() + ".tmp." + ProcessHandle.current().pid());
                    try {
                        StringBuilder sb = new StringBuilder(header(specText, seed, openingPlies));
                        for (Future<Game> fg : perShard.get(i)) {
                            sb.append(line(fg.get()));
                        }
                        Files.writeString(tmp, sb.toString());
                        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE);
                        ok++;
                    } catch (Exception e) {
                        System.err.println("shard " + f + " failed: " + e);
                        Files.deleteIfExists(tmp);
                        err++;
                    }
                }
            }
            System.err.printf(Locale.ROOT, "%d shards ok, %d skip, %d error, %d left%s%n", ok, skip, err,
                    shards - ok - skip, ok + skip == shards ? " - nothing more to do" : "");
            return err == 0 ? 0 : 1;
        }
    }
}
