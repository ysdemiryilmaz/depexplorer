package com.mirth.connect.plugins.depexplorer.server;

/**
 * Makes sure reading the configuration cannot push the running Mirth into an OutOfMemoryError: channels are
 * read one batch at a time, and only when the heap has room to spare.
 */
final class Guard {

    private static final long MB = 1024L * 1024L;
    /** Always left free for Mirth itself. */
    private static final long RESERVE = 96 * MB;

    private Guard() {}

    static void check(int channelsToRead) {
        long need = Math.min(256 * MB, 32 * MB + channelsToRead * 256L * 1024L);
        Runtime r = Runtime.getRuntime();
        long free = r.maxMemory() - (r.totalMemory() - r.freeMemory()) - RESERVE;
        if (free < need) {
            System.gc();
            free = r.maxMemory() - (r.totalMemory() - r.freeMemory()) - RESERVE;
        }
        if (free < need) {
            throw new IllegalStateException(String.format("Not enough free memory in Mirth to read the channels: about %d MB free, about %d MB needed (heap %d MB). "
                    + "Try again when Mirth is less busy, or give Mirth more heap (-Xmx).", Math.max(0, free / MB), need / MB, r.maxMemory() / MB));
        }
    }
}
