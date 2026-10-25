package io.freemytv;

import java.nio.charset.StandardCharsets;

/**
 * A QR code for a short text (the phone pages' addresses, Wallpaper and the remote): byte mode, error correction M,
 * versions 1 to 6 (up to 106 bytes), mask 0. After Project Nayuki's QR Code generator (MIT), cut down to what a URL
 * needs: no version information (from version 7) and no mask search (any mask is valid; phones read mask 0 fine).
 */
final class QrCode {
    private QrCode() {}

    // Error correction M, versions 1 to 6: codewords per block, number of blocks, all codewords.
    private static final int[] ECC = {10, 16, 26, 18, 24, 16};
    private static final int[] BLOCKS = {1, 1, 1, 2, 2, 4};
    private static final int[] RAW = {26, 44, 70, 100, 134, 172};

    /** The modules, [y][x], true = dark; null when the text is too long. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int v = 0;
        while (v < 6 && 12 + 8 * data.length > 8 * (RAW[v] - ECC[v] * BLOCKS[v])) v++;
        if (v == 6) return null;
        int capacity = RAW[v] - ECC[v] * BLOCKS[v];

        // Data: byte mode, the length, the bytes, a terminator, then padding.
        byte[] codewords = new byte[capacity];
        int bit = 0;
        bit = put(codewords, bit, 0b0100, 4);
        bit = put(codewords, bit, data.length, 8);
        for (byte b : data) bit = put(codewords, bit, b & 0xFF, 8);
        bit = put(codewords, bit, 0, Math.min(4, capacity * 8 - bit));
        bit = (bit + 7) / 8 * 8;
        for (int pad = 0xEC; bit < capacity * 8; pad ^= 0xEC ^ 0x11) bit = put(codewords, bit, pad, 8);

        byte[] all = interleave(codewords, v);
        int size = 21 + 4 * v;
        boolean[][] modules = new boolean[size][size];
        boolean[][] function = new boolean[size][size];
        drawFunctions(modules, function, v);
        drawCodewords(modules, function, all);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) if (!function[y][x] && (x + y) % 2 == 0) modules[y][x] ^= true;   // mask 0
        }
        drawFormat(modules, function);
        return modules;
    }

    /** The code as a bitmap `px` wide (dark on white, with the 4-module quiet zone), or null when it doesn't fit. */
    static android.graphics.Bitmap bitmap(String text, int px) {
        boolean[][] m = encode(text);
        if (m == null) return null;
        int n = m.length + 8, scale = Math.max(1, px / n);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(n * scale, n * scale, android.graphics.Bitmap.Config.RGB_565);
        b.eraseColor(0xFFFFFFFF);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint dark = new android.graphics.Paint();
        dark.setColor(0xFF000000);
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m.length; x++) {
                if (m[y][x]) c.drawRect((x + 4) * scale, (y + 4) * scale, (x + 5) * scale, (y + 5) * scale, dark);
            }
        }
        return b;
    }

    private static int put(byte[] out, int bit, int value, int length) {
        for (int i = length - 1; i >= 0; i--, bit++) {
            if (((value >>> i) & 1) != 0) out[bit >>> 3] |= (byte) (0x80 >>> (bit & 7));
        }
        return bit;
    }

    /** Splits the data into blocks, adds each block's error correction and interleaves them. */
    private static byte[] interleave(byte[] data, int v) {
        int blocks = BLOCKS[v], ecc = ECC[v], raw = RAW[v];
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = divisor(ecc);
        byte[][] out = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] dat = java.util.Arrays.copyOfRange(data, k, k + len);
            k += len;
            byte[] block = java.util.Arrays.copyOf(dat, shortLen + 1);
            byte[] rem = remainder(dat, divisor);
            System.arraycopy(rem, 0, block, block.length - ecc, ecc);
            out[i] = block;
        }
        byte[] result = new byte[raw];
        int n = 0;
        for (int i = 0; i < out[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                // Short blocks have one byte less: skip the gap left before their error correction.
                if (i != shortLen - ecc || j >= shortBlocks) result[n++] = out[j][i];
            }
        }
        return result;
    }

    private static void drawFunctions(boolean[][] m, boolean[][] f, int v) {
        int size = m.length;
        for (int i = 0; i < size; i++) {
            set(m, f, 6, i, i % 2 == 0);
            set(m, f, i, 6, i % 2 == 0);
        }
        finder(m, f, 3, 3);
        finder(m, f, size - 4, 3);
        finder(m, f, 3, size - 4);
        if (v > 0) {
            int c = size - 7;   // versions 2 to 6: one alignment pattern, at (size − 7, size − 7)
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) set(m, f, c + dx, c + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormat(m, f);   // reserves the format areas (the real bits come after masking)
    }

    private static void finder(boolean[][] m, boolean[][] f, int cx, int cy) {
        int size = m.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
                if (x >= 0 && x < size && y >= 0 && y < size) set(m, f, x, y, d != 2 && d != 4);
            }
        }
    }

    /** Error correction M (0b00) and mask 0, with their BCH code. */
    private static void drawFormat(boolean[][] m, boolean[][] f) {
        int size = m.length, data = 0;   // (0b00 << 3) | mask 0
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(m, f, 8, i, bit(bits, i));
        set(m, f, 8, 7, bit(bits, 6));
        set(m, f, 8, 8, bit(bits, 7));
        set(m, f, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(m, f, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(m, f, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(m, f, 8, size - 15 + i, bit(bits, i));
        set(m, f, 8, size - 8, true);
    }

    private static void drawCodewords(boolean[][] m, boolean[][] f, byte[] data) {
        int size = m.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!f[y][x] && i < data.length * 8) {
                        m[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void set(boolean[][] m, boolean[][] f, int x, int y, boolean dark) {
        m[y][x] = dark;
        f[y][x] = true;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    // Reed–Solomon over GF(2^8), polynomial 0x11D.

    private static byte[] divisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) multiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = multiply(root, 0x02);
        }
        return result;
    }

    private static byte[] remainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) multiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }
}
