package sternhalma.tablebase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.zip.Deflater;

import sternhalma.engine.Geometry;

/**
 * Measurement tool, not part of the production code and not a test (no JUnit annotations): rewrites the rows>=1 race
 * table (10 stones over 36 cells) into "layout L" and writes the sections as raw files, so that real compressed sizes
 * can be measured.
 *
 * <p>Layout L: the 8 cells of row 1 give an 8-bit pattern p; the stones in the 28 cells of rows >= 2 (10 - k of them,
 * k = popcount(p)) are ranked by the combinatorial rank over those 28 cells. States are grouped by k; within a k the
 * patterns come in ascending pattern value, each with a sub-table of C(28, 10-k) bytes.
 *
 * <p>Usage: {@code LayoutLTool <rows1.bin> <rows2.bin> <outdir>}. Writes {@code k0.bin k1.bin k2.bin k3plus.bin} and
 * the mirror-folded {@code *.fold.bin} (patterns p with p < mirror(p) only, palindromic patterns whole) into outdir,
 * then prints 64 KB-block deflate sizes. Run with {@code -Xmx3g}.
 */
public final class LayoutLTool {

    private static final int PIECES = 10;
    private static final int BLOCK = 64 * 1024;

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        Path rows1 = Path.of(args[0]);
        Path rows2 = Path.of(args[1]);
        Path out = Path.of(args[2]);
        Files.createDirectories(out);

        TableFile.Content c1 = TableFile.read(rows1);
        byte[] body = c1.body();
        int[] region = Rows.regionCells(1);
        if (!Arrays.equals(region, c1.header().cells()) || c1.header().pieces() != PIECES) {
            throw new IllegalStateException("rows1.bin region does not match Rows.regionCells(1)");
        }
        Ranking full = new Ranking(region, PIECES);
        if (full.count() != body.length) {
            throw new IllegalStateException("state count mismatch");
        }
        int[] cells28 = Rows.regionCells(2);
        // region index -> bit in the row-1 pattern, or -1; region index -> index among the 28 cells
        int[] row1Cells = Arrays.stream(region).filter(cell -> Geometry.r(cell) == 1).toArray();
        if (row1Cells.length != 8 || cells28.length != 28) {
            throw new IllegalStateException("unexpected region split " + row1Cells.length + "/" + cells28.length);
        }
        int[] bitOf = new int[region.length];
        int[] idx28Of = new int[region.length];
        for (int i = 0; i < region.length; i++) {
            bitOf[i] = indexOf(row1Cells, region[i]);
            idx28Of[i] = indexOf(cells28, region[i]);
        }
        Ranking[] rank28 = new Ranking[9];
        long[] sub = new long[9];
        for (int k = 0; k <= 8; k++) {
            rank28[k] = new Ranking(cells28, PIECES - k);
            sub[k] = rank28[k].count();
        }
        // patterns per k in ascending value; index of a pattern within its k
        int[] patIdx = new int[256];
        List<List<Integer>> patterns = new ArrayList<>();
        for (int k = 0; k <= 8; k++) {
            patterns.add(new ArrayList<>());
        }
        for (int p = 0; p < 256; p++) {
            List<Integer> l = patterns.get(Integer.bitCount(p));
            patIdx[p] = l.size();
            l.add(p);
        }
        // mirror on patterns (needs the region to be closed under the left-right flip)
        boolean symmetric = true;
        int[] mirrorBit = new int[8];
        for (int b = 0; b < 8; b++) {
            mirrorBit[b] = indexOf(row1Cells, Geometry.mirror(row1Cells[b]));
            symmetric &= mirrorBit[b] >= 0;
        }
        for (int cell : cells28) {
            symmetric &= indexOf(cells28, Geometry.mirror(cell)) >= 0;
        }
        System.err.println("region symmetric under Geometry.mirror: " + symmetric);
        int[] mirrorPat = new int[256];
        for (int p = 0; p < 256; p++) {
            int m = 0;
            for (int b = 0; b < 8; b++) {
                if ((p >> b & 1) != 0) {
                    m |= 1 << mirrorBit[b];
                }
            }
            mirrorPat[p] = m;
        }

        byte[][] section = new byte[9][];
        long total = 0;
        for (int k = 0; k <= 8; k++) {
            long n = patterns.get(k).size() * sub[k];
            section[k] = new byte[Math.toIntExact(n)];
            total += n;
        }
        if (total != body.length) {
            throw new IllegalStateException("layout size " + total + " != " + body.length);
        }

        // one streaming pass over rows1.bin in rank order
        int[] idx = new int[PIECES];
        int[][] idx28 = new int[9][];
        for (int k = 0; k <= 8; k++) {
            idx28[k] = new int[PIECES - k];
        }
        long written = 0;
        for (int r = 0; r < body.length; r++) {
            full.unrankIndices(r, idx);
            int pattern = 0;
            int m = 0;
            int k = 0;
            for (int j = 0; j < PIECES; j++) {
                if (bitOf[idx[j]] >= 0) {
                    k++;
                }
            }
            int[] t = idx28[k];
            for (int j = 0; j < PIECES; j++) {
                int i = idx[j];
                if (bitOf[i] >= 0) {
                    pattern |= 1 << bitOf[i];
                } else {
                    t[m++] = idx28Of[i];
                }
            }
            long r28 = rank28[k].rankIndices(t);
            section[k][Math.toIntExact(patIdx[pattern] * sub[k] + r28)] = body[r];
            written++;
        }
        System.err.printf("converted %d states (%.1f s)%n", written, (System.nanoTime() - t0) / 1e9);

        // verification 1: sample the other way round, (pattern, rank28) -> cells -> rank36 -> value
        SplittableRandom rnd = new SplittableRandom(20261118L);
        int bad = 0;
        int badMirror = 0;
        int samples = 1_000_000;
        for (int s = 0; s < samples; s++) {
            int p = rnd.nextInt(256);
            int k = Integer.bitCount(p);
            long r28 = (long) (rnd.nextDouble() * sub[k]);
            int[] c28 = rank28[k].unrank(r28);
            int[] cells = new int[PIECES];
            int n = 0;
            for (int b = 0; b < 8; b++) {
                if ((p >> b & 1) != 0) {
                    cells[n++] = row1Cells[b];
                }
            }
            for (int cell : c28) {
                cells[n++] = cell;
            }
            byte expected = body[Math.toIntExact(full.rank(cells))];
            byte actual = section[k][Math.toIntExact(patIdx[p] * sub[k] + r28)];
            if (expected != actual) {
                bad++;
            }
            int[] mc = new int[PIECES];
            for (int j = 0; j < PIECES; j++) {
                mc[j] = Geometry.mirror(cells[j]);
            }
            if (body[Math.toIntExact(full.rank(mc))] != expected) {
                badMirror++;
            }
        }
        System.err.printf("sample check: %d of %d mismatches (layout vs rows1.bin), %d mirror mismatches%n", bad,
                samples, badMirror);
        if (bad != 0) {
            throw new IllegalStateException("conversion check failed");
        }

        // verification 2: k0 equals rows2.bin body
        TableFile.Content c2 = TableFile.read(rows2);
        boolean k0Equal = Arrays.equals(c2.body(), section[0]);
        System.err.println("k0 equals rows2.bin body byte for byte: " + k0Equal);

        // folded sections
        byte[][] fold = new byte[9][];
        for (int k = 0; k <= 8; k++) {
            int n = 0;
            for (int p : patterns.get(k)) {
                n += p <= mirrorPat[p] ? 1 : 0;
            }
            fold[k] = new byte[Math.toIntExact(n * sub[k])];
            int at = 0;
            for (int p : patterns.get(k)) {
                if (p <= mirrorPat[p]) {
                    System.arraycopy(section[k], Math.toIntExact(patIdx[p] * sub[k]), fold[k], at, (int) sub[k]);
                    at += (int) sub[k];
                }
            }
        }

        byte[] k3 = concat(section, 3, 8);
        byte[] k3f = concat(fold, 3, 8);
        Files.write(out.resolve("k0.bin"), section[0]);
        Files.write(out.resolve("k1.bin"), section[1]);
        Files.write(out.resolve("k2.bin"), section[2]);
        Files.write(out.resolve("k3plus.bin"), k3);
        Files.write(out.resolve("k0.fold.bin"), fold[0]);
        Files.write(out.resolve("k1.fold.bin"), fold[1]);
        Files.write(out.resolve("k2.fold.bin"), fold[2]);
        Files.write(out.resolve("k3plus.fold.bin"), k3f);
        Files.write(out.resolve("patterns.txt"), (patterns.toString() + "\n").getBytes());
        System.err.printf("files written (%.1f s)%n", (System.nanoTime() - t0) / 1e9);

        // 64 KB block deflate, level 9, gzip framing (18 bytes per block)
        String[] names = {"rows1 dense", "k0", "k<=1", "k<=2", "all k", "k0 fold", "k<=1 fold", "k<=2 fold",
                "all k fold"};
        byte[][][] sets = {
            {body},
            {section[0]},
            {section[0], section[1]},
            {section[0], section[1], section[2]},
            {section[0], section[1], section[2], k3},
            {fold[0]},
            {fold[0], fold[1]},
            {fold[0], fold[1], fold[2]},
            {fold[0], fold[1], fold[2], k3f}};
        for (int i = 0; i < sets.length; i++) {
            long raw = Arrays.stream(sets[i]).mapToLong(a -> a.length).sum();
            long z = blockDeflate(sets[i]);
            System.out.printf("block64k-gzip9  %-12s raw %12d  compressed %12d  (%.2f MB, %.2f%%)%n", names[i], raw, z,
                    z / 1e6, 100.0 * z / raw);
        }
        System.err.printf("done (%.1f s)%n", (System.nanoTime() - t0) / 1e9);
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == v) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] concat(byte[][] parts, int from, int to) {
        int n = 0;
        for (int k = from; k <= to; k++) {
            n += parts[k].length;
        }
        byte[] r = new byte[n];
        int at = 0;
        for (int k = from; k <= to; k++) {
            System.arraycopy(parts[k], 0, r, at, parts[k].length);
            at += parts[k].length;
        }
        return r;
    }

    /** Total size of independently gzip-9 compressed 64 KB blocks of the concatenation of {@code parts}. */
    private static long blockDeflate(byte[][] parts) {
        byte[] block = new byte[BLOCK];
        byte[] buf = new byte[BLOCK * 2];
        int fill = 0;
        long total = 0;
        Deflater d = new Deflater(9, true);
        for (byte[] part : parts) {
            int at = 0;
            while (at < part.length) {
                int n = Math.min(BLOCK - fill, part.length - at);
                System.arraycopy(part, at, block, fill, n);
                fill += n;
                at += n;
                if (fill == BLOCK) {
                    total += deflate(d, block, fill, buf) + 18;
                    fill = 0;
                }
            }
        }
        if (fill > 0) {
            total += deflate(d, block, fill, buf) + 18;
        }
        d.end();
        return total;
    }

    private static int deflate(Deflater d, byte[] in, int len, byte[] buf) {
        d.reset();
        d.setInput(in, 0, len);
        d.finish();
        int n = 0;
        while (!d.finished()) {
            n += d.deflate(buf, 0, buf.length);
        }
        return n;
    }
}
