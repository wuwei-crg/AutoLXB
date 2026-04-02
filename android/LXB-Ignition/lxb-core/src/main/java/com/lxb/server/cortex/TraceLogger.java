package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSONL trace ring buffer for on-device Cortex bootstrap.
 *
 * "Pull" model: PC/debugger asks for last N lines, device returns JSONL.
 */
public class TraceLogger {

    public static final class PullItem {
        public final long seq;
        public final String line;

        public PullItem(long seq, String line) {
            this.seq = seq;
            this.line = line != null ? line : "";
        }
    }

    public static final class PullPage {
        public final List<PullItem> items;
        public final boolean hasMoreBefore;
        public final boolean hasMoreAfter;
        public final long oldestSeq;
        public final long newestSeq;

        public PullPage(
                List<PullItem> items,
                boolean hasMoreBefore,
                boolean hasMoreAfter,
                long oldestSeq,
                long newestSeq
        ) {
            this.items = items != null ? items : new ArrayList<PullItem>();
            this.hasMoreBefore = hasMoreBefore;
            this.hasMoreAfter = hasMoreAfter;
            this.oldestSeq = oldestSeq;
            this.newestSeq = newestSeq;
        }
    }

    private static final class TraceRecord {
        final long seq;
        final String line;

        TraceRecord(long seq, String line) {
            this.seq = seq;
            this.line = line != null ? line : "";
        }
    }

    private final ArrayDeque<TraceRecord> ring;
    private final int capacity;
    private long nextSeq = 1L;
    private final SimpleDateFormat tsFmt =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    // Optional push target for streaming selected trace events over TCP.
    // For now this is used by the Cortex FSM to push per-task progress to
    // the Android front-end chat UI.
    private String pushHost;
    private int pushPort;
    private String pushTaskId;

    public TraceLogger(int capacity) {
        this.capacity = Math.max(10, capacity);
        this.ring = new ArrayDeque<>(this.capacity);
    }

    /**
     * Attach a push target for streaming trace events of a single task.
     * The caller is responsible for ensuring only one active task uses
     * this at a time; the FSM engine currently guarantees that.
     */
    public synchronized void setPushTarget(String host, int port, String taskId) {
        this.pushHost = (host != null && !host.isEmpty()) ? host : null;
        this.pushPort = port;
        this.pushTaskId = (taskId != null && !taskId.isEmpty()) ? taskId : null;
    }

    /**
     * Clear push target. If taskId is non-null, only clears when it matches
     * the currently attached task, to avoid accidental interference.
     */
    public synchronized void clearPushTarget(String taskId) {
        if (this.pushTaskId == null) {
            this.pushHost = null;
            this.pushPort = 0;
            return;
        }
        if (taskId == null || this.pushTaskId.equals(taskId)) {
            this.pushHost = null;
            this.pushPort = 0;
            this.pushTaskId = null;
        }
    }

    public void event(String event, Map<String, Object> fields) {
        Map<String, Object> o = (fields != null)
                ? new LinkedHashMap<>(fields)
                : new LinkedHashMap<String, Object>();
        o.put("ts", tsFmt.format(new Date()));
        o.put("event", event);

        String line = Json.stringify(o);
        synchronized (this) {
            appendLine(line);
        }

        pushIfNeeded(o);
    }

    public void event(String event) {
        event(event, null);
    }

    private void appendLine(String line) {
        while (ring.size() >= capacity) {
            ring.pollFirst();
        }
        ring.addLast(new TraceRecord(nextSeq++, line));
    }

    public synchronized PullPage pullTail(int limit) {
        int n = normalizeLimit(limit);
        List<PullItem> all = snapshotAll();
        if (all.isEmpty()) {
            return new PullPage(new ArrayList<PullItem>(), false, false, 0L, 0L);
        }
        int start = Math.max(0, all.size() - n);
        List<PullItem> items = new ArrayList<>(all.subList(start, all.size()));
        return buildPage(items, start > 0, false);
    }

    public synchronized PullPage pullBefore(long beforeSeq, int limit) {
        int n = normalizeLimit(limit);
        List<PullItem> matched = new ArrayList<>();
        for (TraceRecord record : ring) {
            if (record.seq < beforeSeq) {
                matched.add(new PullItem(record.seq, record.line));
            }
        }
        if (matched.isEmpty()) {
            return new PullPage(new ArrayList<PullItem>(), false, false, 0L, 0L);
        }
        int start = Math.max(0, matched.size() - n);
        List<PullItem> items = new ArrayList<>(matched.subList(start, matched.size()));
        return buildPage(items, start > 0, false);
    }

    public synchronized PullPage pullAfter(long afterSeq, int limit) {
        int n = normalizeLimit(limit);
        List<PullItem> matched = new ArrayList<>();
        for (TraceRecord record : ring) {
            if (record.seq > afterSeq) {
                matched.add(new PullItem(record.seq, record.line));
            }
        }
        if (matched.isEmpty()) {
            return new PullPage(new ArrayList<PullItem>(), false, false, 0L, 0L);
        }
        boolean hasMoreAfter = matched.size() > n;
        List<PullItem> items = new ArrayList<>(matched.subList(0, Math.min(n, matched.size())));
        return buildPage(items, false, hasMoreAfter);
    }

    private PullPage buildPage(List<PullItem> items, boolean hasMoreBefore, boolean hasMoreAfter) {
        if (items == null || items.isEmpty()) {
            return new PullPage(new ArrayList<PullItem>(), false, false, 0L, 0L);
        }
        long oldestSeq = items.get(0).seq;
        long newestSeq = items.get(items.size() - 1).seq;
        return new PullPage(items, hasMoreBefore, hasMoreAfter, oldestSeq, newestSeq);
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, capacity));
    }

    private List<PullItem> snapshotAll() {
        List<PullItem> out = new ArrayList<>(ring.size());
        for (TraceRecord record : ring) {
            out.add(new PullItem(record.seq, record.line));
        }
        return out;
    }

    private static int utf8Length(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Best-effort TCP push of a single trace JSON object. Only events that
     * belong to the currently attached task_id are sent.
     */
    private void pushIfNeeded(Map<String, Object> o) {
        String host;
        int port;
        String taskId;
        synchronized (this) {
            host = this.pushHost;
            port = this.pushPort;
            taskId = this.pushTaskId;
        }
        if (host == null || host.isEmpty() || port <= 0) {
            return;
        }
        Object tid = o.get("task_id");
        if (tid == null) {
            return;
        }
        String eventTaskId = String.valueOf(tid);
        if (taskId != null && !taskId.equals(eventTaskId)) {
            return;
        }

        try {
            byte[] data = Json.stringify(o).getBytes(StandardCharsets.UTF_8);
            if (data.length == 0 || data.length > 60000) {
                return;
            }

            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 300);
                socket.setSoTimeout(300);
                socket.getOutputStream().write(data);
                socket.getOutputStream().write('\n');
                socket.getOutputStream().flush();
            } finally {
                socket.close();
            }
        } catch (Exception ignored) {
            // Push is best-effort only; never interfere with local logging.
        }
    }
}
