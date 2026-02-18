package com.lxb.server.daemon;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Short-lived duplicate tracker for UDP retries.
 *
 * It intentionally keeps no connection/session state; it only deduplicates
 * identical frames in a brief TTL window.
 */
public class SequenceTracker {

    private static final int WINDOW_SIZE = 256;
    private static final long WINDOW_TTL_MS = 3000L;

    private final LinkedHashMap<FrameKey, Long> receiveWindow;

    public SequenceTracker() {
        this.receiveWindow = new LinkedHashMap<>(WINDOW_SIZE, 0.75f, true);
    }

    /**
     * Returns true iff the exact same frame fingerprint was seen recently.
     */
    public synchronized boolean isDuplicate(int seq, byte cmd, byte[] payload) {
        pruneExpired();
        FrameKey key = new FrameKey(seq, cmd, payload);
        if (receiveWindow.containsKey(key)) {
            System.out.println("[Daemon] Duplicate frame detected: seq=" + seq +
                    ", cmd=0x" + String.format("%02X", cmd & 0xFF));
            return true;
        }

        receiveWindow.put(key, System.currentTimeMillis());
        pruneOverflow();
        return false;
    }

    public synchronized void clear() {
        receiveWindow.clear();
    }

    private void pruneExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<FrameKey, Long>> it = receiveWindow.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<FrameKey, Long> e = it.next();
            if (now - e.getValue() > WINDOW_TTL_MS) {
                it.remove();
            }
        }
    }

    private void pruneOverflow() {
        while (receiveWindow.size() > WINDOW_SIZE) {
            FrameKey first = receiveWindow.keySet().iterator().next();
            receiveWindow.remove(first);
        }
    }

    public static final class FrameKey {
        public final int seq;
        public final byte cmd;
        public final int payloadHash;

        public FrameKey(int seq, byte cmd, byte[] payload) {
            this.seq = seq;
            this.cmd = cmd;
            this.payloadHash = Arrays.hashCode(payload == null ? new byte[0] : payload);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FrameKey)) return false;
            FrameKey other = (FrameKey) o;
            return seq == other.seq && cmd == other.cmd && payloadHash == other.payloadHash;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(seq);
            result = 31 * result + Byte.hashCode(cmd);
            result = 31 * result + Integer.hashCode(payloadHash);
            return result;
        }
    }
}
