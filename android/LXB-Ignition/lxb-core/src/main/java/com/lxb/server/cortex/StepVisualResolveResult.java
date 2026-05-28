package com.lxb.server.cortex;

import java.util.Locale;

public final class StepVisualResolveResult {
    public static final String STATUS_POINT = "point";
    public static final String STATUS_NO_MATCH = "no_match";
    public static final String STATUS_AMBIGUOUS = "ambiguous";
    public static final String STATUS_BLOCKED = "blocked";
    public static final String STATUS_ERROR = "error";

    public final String status;
    public final int x;
    public final int y;
    public final String reason;
    public final String resolverName;

    private StepVisualResolveResult(String status, int x, int y, String reason, String resolverName) {
        this.status = normalizeStatus(status);
        this.x = x;
        this.y = y;
        this.reason = reason != null ? reason : "";
        this.resolverName = resolverName != null ? resolverName : "";
    }

    public static StepVisualResolveResult point(int x, int y, String reason, String resolverName) {
        return new StepVisualResolveResult(STATUS_POINT, x, y, reason, resolverName);
    }

    public static StepVisualResolveResult status(String status, String reason, String resolverName) {
        return new StepVisualResolveResult(status, -1, -1, reason, resolverName);
    }

    public boolean isPoint() {
        return STATUS_POINT.equals(status);
    }

    public boolean isBlocked() {
        return STATUS_BLOCKED.equals(status);
    }

    public static String normalizeStatus(String status) {
        String v = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (STATUS_POINT.equals(v)
                || STATUS_NO_MATCH.equals(v)
                || STATUS_AMBIGUOUS.equals(v)
                || STATUS_BLOCKED.equals(v)
                || STATUS_ERROR.equals(v)) {
            return v;
        }
        return STATUS_ERROR;
    }
}
