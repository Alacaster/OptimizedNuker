package dev.firstmage.optimizednuker.modules;

import net.minecraft.util.math.BlockPos;

/**
 * Fixed-size primitive storage for crawl candidates.
 *
 * The crawl scanner owns ordering. The high-side queue is appended in ascending
 * map-index order and read from the front. The low-side queue is filled while
 * walking downward from the anchor and read from the back, which exposes the
 * lowest queued map index first. The work set always consumes low-side work
 * before high-side fallback work.
 */
final class NukerActionQueue {
    private final long[] posLongs;
    private final int[] mapIndices;
    private final float[] distances;
    private final byte[] types;
    private int count;

    NukerActionQueue(int capacity) {
        this.posLongs = new long[capacity];
        this.mapIndices = new int[capacity];
        this.distances = new float[capacity];
        this.types = new byte[capacity];
        this.count = 0;
    }

    int capacity() {
        return this.posLongs.length;
    }

    boolean isEmpty() {
        return this.count == 0;
    }

    boolean isFull() {
        return this.count >= this.posLongs.length;
    }

    int size() {
        return this.count;
    }

    void clear() {
        this.count = 0;
    }

    boolean append(long posLong, int mapIndex, float distance, byte type) {
        if (isFull()) return false;
        int i = this.count;
        this.posLongs[i] = posLong;
        this.mapIndices[i] = mapIndex;
        this.distances[i] = distance;
        this.types[i] = type;
        this.count++;
        return true;
    }

    boolean peekFirstInto(View view) {
        return readInto(0, view);
    }

    boolean peekLastInto(View view) {
        return readInto(this.count - 1, view);
    }

    boolean popFirstInto(View view) {
        if (!readInto(0, view)) return false;
        int move = this.count - 1;
        if (move > 0) {
            System.arraycopy(this.posLongs, 1, this.posLongs, 0, move);
            System.arraycopy(this.mapIndices, 1, this.mapIndices, 0, move);
            System.arraycopy(this.distances, 1, this.distances, 0, move);
            System.arraycopy(this.types, 1, this.types, 0, move);
        }
        this.count--;
        return true;
    }

    boolean popLastInto(View view) {
        int index = this.count - 1;
        if (!readInto(index, view)) return false;
        this.count--;
        return true;
    }

    private boolean readInto(int index, View view) {
        if (index < 0 || index >= this.count) return false;
        view.posLong = this.posLongs[index];
        view.mapIndex = this.mapIndices[index];
        view.distance = this.distances[index];
        view.type = this.types[index];

        // Unpack to absolute block coordinates. x is sign-extended by the arithmetic
        // shift; y and z need explicit two's-complement correction after their masks.
        long packed = view.posLong;
        int x = (int) (packed >> 38);
        int y = (int) (packed & 0xFFFL);
        int z = (int) ((packed >> 12) & 0x3FFFFFFL);
        if (y >= 0x800) y -= 0x1000;
        if (z >= 0x2000000) z -= 0x4000000;
        view.pos.set(x, y, z);
        return true;
    }

    static final class View {
        final BlockPos.Mutable pos = new BlockPos.Mutable();
        long posLong;
        int mapIndex;
        float distance;
        byte type;
    }
}
