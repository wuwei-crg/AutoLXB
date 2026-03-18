package com.lxb.server.cortex;

import com.lxb.server.cortex.json.Json;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
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

    private final ArrayDeque<String> ring;
    private final int capacity;
    private final SimpleDateFormat tsFmt =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    // Optional push target for streaming selected trace events over UDP.
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
        ring.addLast(line);
    }

    public synchronized String dumpLastLines(int maxLines) {
        int n = Math.max(1, Math.min(maxLines, capacity));
        int skip = Math.max(0, ring.size() - n);

        // UDP/frame payload has an upper bound; keep response safely under that limit.
        // Use UTF-8 byte size (not char count) to avoid underestimating non-ASCII lines.
        final int maxBytes = 60000;

        List<String> lines = new ArrayList<>(n);
        int i = 0;
        for (String line : ring) {
            if (i++ < skip) {
                continue;
            }
            lines.add(line);
        }

        int totalBytes = 0;
        for (String line : lines) {
            totalBytes += utf8Length(line) + 1; // '\n'
        }

        int droppedHead = 0;
        while (totalBytes > maxBytes && !lines.isEmpty()) {
            String dropped = lines.remove(0);
            totalBytes -= utf8Length(dropped) + 1;
            droppedHead += 1;
        }

        String marker = null;
        if (droppedHead > 0) {
            Map<String, Object> markerObj = new LinkedHashMap<>();
            markerObj.put("event", "trace_truncated");
            markerObj.put("reason", "trace_dump_exceeds_limit");
            markerObj.put("drop_head_lines", droppedHead);
            markerObj.put("kept_tail_lines", lines.size());
            marker = Json.stringify(markerObj);
            int markerBytes = utf8Length(marker) + 1;
            while (!lines.isEmpty() && totalBytes + markerBytes > maxBytes) {
                String dropped = lines.remove(0);
                totalBytes -= utf8Length(dropped) + 1;
            }
        }

        StringBuilder sb = new StringBuilder();
        if (marker != null) {
            sb.append(marker).append('\n');
        }
        for (String line : lines) {
            sb.append(line).append('\n');
        }

        return sb.toString();
    }

    private static int utf8Length(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Best-effort UDP push of a single trace JSON object. Only events that
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
            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    InetAddress.getByName(host),
                    port
            );
            DatagramSocket socket = new DatagramSocket();
            try {
                socket.send(packet);
            } finally {
                socket.close();
            }
        } catch (Exception ignored) {
            // Push is best-effort only; never interfere with local logging.
        }
    }
}
