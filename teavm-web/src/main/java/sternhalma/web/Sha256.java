package sternhalma.web;

/**
 * SHA-256 in plain Java. TeaVM's class library has no {@code java.security.MessageDigest}, and the page has
 * {@code crypto.subtle} only in a secure context. Checked against MessageDigest in {@code Sha256Test}.
 */
final class Sha256 {
    private static final int[] K = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

    private Sha256() {
    }

    /** The digest of {@code data[from, to)}. */
    static byte[] digest(byte[] data, int from, int to) {
        int[] h = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
        int[] w = new int[64];
        long length = (long) to - from;
        int full = (int) (length / 64);
        for (int block = 0; block < full; block++) {
            compress(h, w, data, from + block * 64);
        }
        // padding: 0x80, zeros, the length in bits as 64 bit big endian
        byte[] tail = new byte[(int) (length % 64) < 56 ? 64 : 128];
        int rest = (int) (length % 64);
        System.arraycopy(data, from + full * 64, tail, 0, rest);
        tail[rest] = (byte) 0x80;
        long bits = length * 8;
        for (int i = 0; i < 8; i++) {
            tail[tail.length - 1 - i] = (byte) (bits >>> (8 * i));
        }
        for (int at = 0; at < tail.length; at += 64) {
            compress(h, w, tail, at);
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[4 * i] = (byte) (h[i] >>> 24);
            out[4 * i + 1] = (byte) (h[i] >>> 16);
            out[4 * i + 2] = (byte) (h[i] >>> 8);
            out[4 * i + 3] = (byte) h[i];
        }
        return out;
    }

    private static void compress(int[] h, int[] w, byte[] data, int at) {
        for (int i = 0; i < 16; i++) {
            int p = at + 4 * i;
            w[i] = (data[p] & 0xff) << 24 | (data[p + 1] & 0xff) << 16 | (data[p + 2] & 0xff) << 8 | (data[p + 3] & 0xff);
        }
        for (int i = 16; i < 64; i++) {
            int s0 = Integer.rotateRight(w[i - 15], 7) ^ Integer.rotateRight(w[i - 15], 18) ^ (w[i - 15] >>> 3);
            int s1 = Integer.rotateRight(w[i - 2], 17) ^ Integer.rotateRight(w[i - 2], 19) ^ (w[i - 2] >>> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        int a = h[0];
        int b = h[1];
        int c = h[2];
        int d = h[3];
        int e = h[4];
        int f = h[5];
        int g = h[6];
        int hh = h[7];
        for (int i = 0; i < 64; i++) {
            int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
            int ch = (e & f) ^ (~e & g);
            int t1 = hh + s1 + ch + K[i] + w[i];
            int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
            int maj = (a & b) ^ (a & c) ^ (b & c);
            int t2 = s0 + maj;
            hh = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        h[0] += a;
        h[1] += b;
        h[2] += c;
        h[3] += d;
        h[4] += e;
        h[5] += f;
        h[6] += g;
        h[7] += hh;
    }
}
