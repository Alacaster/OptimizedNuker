package dev.firstmage.optimizednuker.modules;

import net.minecraft.util.math.BlockPos;

/**
 * Tiny insertion-sorted queue for scanner candidates.
 *
 * This is deliberately boring and allocation-light:
 * - fixed primitive arrays
 * - explicit count
 * - no duplicate checks
 * - sorted by the same priority rule the scanners already use
 *
 * The queue stores only packed fields. Callers can project a slot into a reusable
 * View when they actually need a BlockPos or other structured access.
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

    int size() {
        return this.count;
    }

    boolean isEmpty() {
        return this.count == 0;
    }

    boolean isFull() {
        return this.count >= this.posLongs.length;
    }

    void clear() {
        this.count = 0;
    }

    void insertSorted(long posLong, int mapIndex, float distance, byte type) {
        if (this.posLongs.length == 0) return;

        int insertAt = this.count;
        while (insertAt > 0 && compare(posLong, mapIndex, type, insertAt - 1) < 0) {
            insertAt--;
        }

        if (this.count < this.posLongs.length) {
            shiftRight(insertAt);
            write(insertAt, posLong, mapIndex, distance, type);
            this.count++;
            return;
        }

        if (insertAt >= this.count) {
            return;
        }

        shiftRightDropLast(insertAt);
        write(insertAt, posLong, mapIndex, distance, type);
    }

    boolean readFirstInto(View view) {
        return readInto(0, view);
    }

    boolean popFirstInto(View view) {
        if (!readInto(0, view)) return false;
        removeAt(0);
        return true;
    }

    boolean readInto(int index, View view) {
        if (index < 0 || index >= this.count) return false;
        view.posLong = this.posLongs[index];
        view.mapIndex = this.mapIndices[index];
        view.distance = this.distances[index];
        view.type = this.types[index];

        long packed = view.posLong;
        int x = (int) (packed >> 38);
        int y = (int) (packed & 0xFFFL);
        int z = (int) ((packed >> 12) & 0x3FFFFFFL);
        if (x >= 0x2000000) x -= 0x4000000;
        if (y >= 0x800) y -= 0x1000;
        if (z >= 0x2000000) z -= 0x4000000;
        view.pos.set(x, y, z);
        return true;
    }

    void removeFirst() {
        removeAt(0);
    }

    void removeAt(int index) {
        if (index < 0 || index >= this.count) return;
        int move = this.count - index - 1;
        if (move > 0) {
            System.arraycopy(this.posLongs, index + 1, this.posLongs, index, move);
            System.arraycopy(this.mapIndices, index + 1, this.mapIndices, index, move);
            System.arraycopy(this.distances, index + 1, this.distances, index, move);
            System.arraycopy(this.types, index + 1, this.types, index, move);
        }
        this.count--;
    }

    private void shiftRight(int insertAt) {
        int move = this.count - insertAt;
        if (move > 0) {
            System.arraycopy(this.posLongs, insertAt, this.posLongs, insertAt + 1, move);
            System.arraycopy(this.mapIndices, insertAt, this.mapIndices, insertAt + 1, move);
            System.arraycopy(this.distances, insertAt, this.distances, insertAt + 1, move);
            System.arraycopy(this.types, insertAt, this.types, insertAt + 1, move);
        }
    }

    private void shiftRightDropLast(int insertAt) {
        int move = this.count - insertAt - 1;
        if (move > 0) {
            System.arraycopy(this.posLongs, insertAt, this.posLongs, insertAt + 1, move);
            System.arraycopy(this.mapIndices, insertAt, this.mapIndices, insertAt + 1, move);
            System.arraycopy(this.distances, insertAt, this.distances, insertAt + 1, move);
            System.arraycopy(this.types, insertAt, this.types, insertAt + 1, move);
        }
    }

    private void write(int index, long posLong, int mapIndex, float distance, byte type) {
        this.posLongs[index] = posLong;
        this.mapIndices[index] = mapIndex;
        this.distances[index] = distance;
        this.types[index] = type;
    }

    private int compare(long posLong, int mapIndex, byte type, int existingIndex) {
        int cmp = Integer.compare(mapIndex, this.mapIndices[existingIndex]);
        if (cmp != 0) return cmp;
        cmp = Long.compare(posLong, this.posLongs[existingIndex]);
        if (cmp != 0) return cmp;
        return Byte.compare(type, this.types[existingIndex]);
    }

    static final class View {
        final BlockPos.Mutable pos = new BlockPos.Mutable();
        long posLong;
        int mapIndex;
        float distance;
        byte type;
    }
}
