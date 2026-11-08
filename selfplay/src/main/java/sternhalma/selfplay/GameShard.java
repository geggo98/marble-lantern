package sternhalma.selfplay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sternhalma.engine.Geometry;
import sternhalma.engine.RuleSet;
import sternhalma.selfplay.Adjudication.Firing;
import sternhalma.selfplay.Adjudication.Layer;
import sternhalma.selfplay.SampleFile.Tensor;

/**
 * A shard of the self-play data set: the games {@code firstGame .. firstGame + games - 1} and their samples in one
 * safetensors file (format {@code sternhalma-samples}, version {@value #FORMAT_VERSION}; docs/training.md,
 * "Self-play sample files"). The game-level tensors beyond the table of training.md carry the adjudication record
 * (label layer and ply, the played-out result of control games, the first firing of every layer) and the
 * per-move search scores, so that adjudication rules can be re-evaluated offline on control games.
 */
public final class GameShard {

    public static final String FORMAT = "sternhalma-samples";
    public static final int FORMAT_VERSION = 1;

    private GameShard() {
    }

    /** Tensors and metadata of a shard. */
    public static void write(Path file, SelfPlayGame.Settings settings, int shard, int firstGame,
            List<GameRecord> games) throws IOException {
        SampleFile.write(file, tensors(games), metadata(settings, shard, firstGame, games.size()));
    }

    static Map<String, String> metadata(SelfPlayGame.Settings settings, int shard, int firstGame, int games) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("format_version", Integer.toString(FORMAT_VERSION));
        m.put("players", "2");
        m.put("rules", RuleSet.defaults(2).toString());
        m.put("generation", "0");
        m.put("network_sha256", "");
        m.put("search", settings.spec());
        m.put("teacher", settings.spec());
        m.put("seed", Long.toString(settings.seed()));
        m.put("selfplay", settings.describe());
        m.put("shard", Integer.toString(shard));
        m.put("first_game", Integer.toString(firstGame));
        m.put("games", Integer.toString(games));
        Teacher.Config t = settings.teacher();
        if (t != null) {
            // E10: the labels are exact root scores of the teacher search, not the options the players moved with
            m.put("teacher_search", Teacher.searchDescription(settings.spec(), t));
            m.put("teacher_depth", Integer.toString(t.depth()));
            m.put("teacher_labels", t.describe());
        }
        return m;
    }

    static Map<String, Tensor> tensors(List<GameRecord> games) {
        int g = games.size();
        int totalMoves = 0;
        int totalSamples = 0;
        int totalLegal = 0;
        for (GameRecord r : games) {
            totalMoves += r.moves().length;
            if (r.samples() != null) {
                totalSamples += r.samples().count();
                totalLegal += r.samples().moveFrom().length;
            }
        }
        int[] id = new int[g];
        short[] opening = new short[g];
        int[] moveOffsets = new int[g + 1];
        int[] moves = new int[totalMoves];
        int[] scores = new int[totalMoves];
        float[] values = new float[g * 2];
        byte[] label = new byte[g];
        short[] labelPly = new short[g];
        short[] finalPly = new short[g];
        float[] finalValues = new float[g * 2];
        short[] s1 = new short[g * 3];
        short[] margin = new short[g * 2];
        short[] eval = new short[g * 2];
        int[] raceIgnored = new int[g];
        int[] cpu = new int[g * 3];
        int[] nodes = new int[g];
        // samples
        byte[] cells = new byte[totalSamples * Geometry.CELLS];
        short[] ply = new short[totalSamples];
        short[] moveLimit = new short[totalSamples];
        int[] sampleMoveOffsets = new int[totalSamples + 1];
        byte[] from = new byte[totalLegal];
        byte[] to = new byte[totalLegal];
        float[] target = new float[totalLegal];
        int[] played = new int[totalSamples];
        float[] valueTarget = new float[totalSamples * 2];
        float[] wp = new float[totalSamples];
        float[] wv = new float[totalSamples];
        byte[] flags = new byte[totalSamples];
        int[] sampleGame = new int[totalSamples];
        int mo = 0;
        int so = 0;
        int lo = 0;
        short limit = (short) RuleSet.defaults(2).moveLimit();
        for (int i = 0; i < g; i++) {
            GameRecord r = games.get(i);
            id[i] = r.id();
            opening[i] = (short) r.openingPlies();
            System.arraycopy(r.moves(), 0, moves, mo, r.moves().length);
            System.arraycopy(r.scores(), 0, scores, mo, r.scores().length);
            mo += r.moves().length;
            moveOffsets[i + 1] = mo;
            values[2 * i] = (float) r.labelValues()[0];
            values[2 * i + 1] = (float) r.labelValues()[1];
            label[i] = (byte) r.label().code;
            labelPly[i] = (short) r.labelPly();
            finalPly[i] = (short) r.finalPly();
            finalValues[2 * i] = r.finalValues() == null ? Float.NaN : (float) r.finalValues()[0];
            finalValues[2 * i + 1] = r.finalValues() == null ? Float.NaN : (float) r.finalValues()[1];
            put(s1, 3 * i, r.s1(), true);
            put(margin, 2 * i, r.margin(), false);
            put(eval, 2 * i, r.eval(), false);
            raceIgnored[i] = r.raceScoresIgnored();
            cpu[3 * i] = micros(r.cpuNanos());
            cpu[3 * i + 1] = micros(r.searchCpuNanos());
            cpu[3 * i + 2] = micros(r.s1CpuNanos());
            nodes[i] = Math.toIntExact(r.nodes());
            GameRecord.Samples s = r.samples();
            if (s == null) {
                continue;
            }
            int n = s.count();
            System.arraycopy(s.cells(), 0, cells, so * Geometry.CELLS, s.cells().length);
            System.arraycopy(s.ply(), 0, ply, so, n);
            System.arraycopy(s.played(), 0, played, so, n);
            System.arraycopy(s.value(), 0, valueTarget, 2 * so, 2 * n);
            System.arraycopy(s.policyWeight(), 0, wp, so, n);
            System.arraycopy(s.valueWeight(), 0, wv, so, n);
            System.arraycopy(s.flags(), 0, flags, so, n);
            System.arraycopy(s.moveFrom(), 0, from, lo, s.moveFrom().length);
            System.arraycopy(s.moveTo(), 0, to, lo, s.moveTo().length);
            System.arraycopy(s.moveTarget(), 0, target, lo, s.moveTarget().length);
            for (int j = 0; j < n; j++) {
                moveLimit[so + j] = limit;
                sampleGame[so + j] = i;
                sampleMoveOffsets[so + j + 1] = sampleMoveOffsets[so + j] + s.moveCounts()[j];
            }
            so += n;
            lo += s.moveFrom().length;
        }
        Map<String, Tensor> t = new LinkedHashMap<>();
        t.put("cells", Tensor.u8(cells, totalSamples, Geometry.CELLS));
        t.put("ply", Tensor.i16(ply, totalSamples));
        t.put("move_limit", Tensor.i16(moveLimit, totalSamples));
        t.put("move_offsets", Tensor.i32(sampleMoveOffsets, totalSamples + 1));
        t.put("move_from", Tensor.u8(from, totalLegal));
        t.put("move_to", Tensor.u8(to, totalLegal));
        t.put("move_target", Tensor.f32(target, totalLegal));
        t.put("played", Tensor.i32(played, totalSamples));
        t.put("value_target", Tensor.f32(valueTarget, totalSamples, 2));
        t.put("policy_weight", Tensor.f32(wp, totalSamples));
        t.put("value_weight", Tensor.f32(wv, totalSamples));
        t.put("flags", Tensor.u8(flags, totalSamples));
        t.put("game", Tensor.i32(sampleGame, totalSamples));
        t.put("game_moves", Tensor.i32(moves, totalMoves));
        t.put("game_move_offsets", Tensor.i32(moveOffsets, g + 1));
        t.put("game_values", Tensor.f32(values, g, 2));
        t.put("game_id", Tensor.i32(id, g));
        t.put("game_opening_plies", Tensor.i16(opening, g));
        t.put("game_scores", Tensor.i32(scores, totalMoves));
        t.put("game_label", Tensor.u8(label, g));
        t.put("game_label_ply", Tensor.i16(labelPly, g));
        t.put("game_final_ply", Tensor.i16(finalPly, g));
        t.put("game_final_values", Tensor.f32(finalValues, g, 2));
        t.put("game_s1", Tensor.i16(s1, g, 3));
        t.put("game_margin", Tensor.i16(margin, g, 2));
        t.put("game_eval", Tensor.i16(eval, g, 2));
        t.put("game_race_scores_ignored", Tensor.i32(raceIgnored, g));
        t.put("game_cpu_us", Tensor.i32(cpu, g, 3));
        t.put("game_nodes", Tensor.i32(nodes, g));
        return t;
    }

    private static int micros(long nanos) {
        return (int) Math.min(Integer.MAX_VALUE, nanos / 1000);
    }

    private static void put(short[] a, int at, Firing f, boolean withEnd) {
        a[at] = (short) (f == null ? -1 : f.ply());
        a[at + 1] = (short) (f == null ? -1 : f.winner());
        if (withEnd) {
            a[at + 2] = (short) (f == null ? -1 : f.endPly());
        }
    }

    private static Firing firing(Layer layer, short[] a, int at, boolean withEnd) {
        return a[at] < 0 ? null : new Firing(layer, a[at], a[at + 1], withEnd ? a[at + 2] : -1);
    }

    /** A shard as read back: its metadata and the game records (samples are not rebuilt). */
    public record Read(Map<String, String> metadata, List<GameRecord> games, SampleFile.Contents contents) {
    }

    /** Reads a shard and checks its format; the samples stay in {@link Read#contents()}. */
    public static Read read(Path file) throws IOException {
        SampleFile.Contents c = SampleFile.read(file);
        Map<String, String> m = c.metadata();
        if (!FORMAT.equals(m.get("format")) || !Integer.toString(FORMAT_VERSION).equals(m.get("format_version"))) {
            throw new IOException(file + ": not a " + FORMAT + " v" + FORMAT_VERSION + " file");
        }
        int[] id = c.tensor("game_id").i32();
        short[] opening = c.tensor("game_opening_plies").i16();
        int[] offsets = c.tensor("game_move_offsets").i32();
        int[] moves = c.tensor("game_moves").i32();
        int[] scores = c.tensor("game_scores").i32();
        float[] values = c.tensor("game_values").f32();
        byte[] label = c.tensor("game_label").u8();
        short[] labelPly = c.tensor("game_label_ply").i16();
        short[] finalPly = c.tensor("game_final_ply").i16();
        float[] finalValues = c.tensor("game_final_values").f32();
        short[] s1 = c.tensor("game_s1").i16();
        short[] margin = c.tensor("game_margin").i16();
        short[] eval = c.tensor("game_eval").i16();
        int[] race = c.tensor("game_race_scores_ignored").i32();
        int[] cpu = c.tensor("game_cpu_us").i32();
        int[] nodes = c.tensor("game_nodes").i32();
        if (offsets.length != id.length + 1 || offsets[id.length] != moves.length || scores.length != moves.length) {
            throw new IOException(file + ": inconsistent game tensors");
        }
        List<GameRecord> games = new ArrayList<>();
        for (int i = 0; i < id.length; i++) {
            boolean playedOut = finalPly[i] >= 0;
            games.add(new GameRecord(id[i], opening[i],
                    java.util.Arrays.copyOfRange(moves, offsets[i], offsets[i + 1]),
                    java.util.Arrays.copyOfRange(scores, offsets[i], offsets[i + 1]),
                    Layer.of(label[i]), labelPly[i], new double[] {values[2 * i], values[2 * i + 1]}, playedOut,
                    finalPly[i], playedOut ? new double[] {finalValues[2 * i], finalValues[2 * i + 1]} : null,
                    firing(Layer.S1, s1, 3 * i, true), firing(Layer.MARGIN, margin, 2 * i, false),
                    firing(Layer.EVAL, eval, 2 * i, false), race[i], cpu[3 * i] * 1000L, cpu[3 * i + 1] * 1000L,
                    cpu[3 * i + 2] * 1000L, nodes[i], null));
        }
        return new Read(m, games, c);
    }
}
