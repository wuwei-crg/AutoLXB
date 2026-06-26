package com.lxb.server.cortex;

import com.lxb.server.cortex.fsm.VisionCommandParser;

import java.util.List;
import java.util.Locale;

public final class TaskMapTapVerificationResult {
    public static final String DECISION_PREVIOUS = "previous";
    public static final String DECISION_CURRENT = "current";
    public static final String DECISION_DEFER = "defer";
    public static final String DECISION_ERROR = "error";

    public final String decision;
    public final String command;
    public final int tapX;
    public final int tapY;
    public final String observing;
    public final String judgingPrev;
    public final String judgePrevResult;
    public final String thinking;
    public final String reason;
    public final String resolverName;

    private TaskMapTapVerificationResult(
            String decision,
            String command,
            int tapX,
            int tapY,
            String observing,
            String judgingPrev,
            String judgePrevResult,
            String thinking,
            String reason,
            String resolverName
    ) {
        this.decision = normalizeDecision(decision);
        this.command = command != null ? command.trim() : "";
        this.tapX = tapX;
        this.tapY = tapY;
        this.observing = observing != null ? observing : "";
        this.judgingPrev = judgingPrev != null ? judgingPrev : "";
        this.judgePrevResult = judgePrevResult != null ? judgePrevResult : "";
        this.thinking = thinking != null ? thinking : "";
        this.reason = reason != null ? reason : "";
        this.resolverName = resolverName != null ? resolverName : "";
    }

    public static TaskMapTapVerificationResult previous(int x, int y, String reason, String resolverName) {
        return previous(x, y, "", "", "", "", reason, resolverName);
    }

    public static TaskMapTapVerificationResult previous(
            int x,
            int y,
            String observing,
            String judgingPrev,
            String judgePrevResult,
            String thinking,
            String reason,
            String resolverName
    ) {
        return new TaskMapTapVerificationResult(
                DECISION_PREVIOUS,
                "TAP " + x + " " + y,
                x,
                y,
                observing,
                judgingPrev,
                judgePrevResult,
                thinking,
                reason,
                resolverName
        );
    }

    public static TaskMapTapVerificationResult current(int x, int y, String reason, String resolverName) {
        return current(x, y, "", "", "", "", reason, resolverName);
    }

    public static TaskMapTapVerificationResult current(
            int x,
            int y,
            String observing,
            String judgingPrev,
            String judgePrevResult,
            String thinking,
            String reason,
            String resolverName
    ) {
        return new TaskMapTapVerificationResult(
                DECISION_CURRENT,
                "TAP " + x + " " + y,
                x,
                y,
                observing,
                judgingPrev,
                judgePrevResult,
                thinking,
                reason,
                resolverName
        );
    }

    public static TaskMapTapVerificationResult defer(String command, String reason, String resolverName) {
        return defer("", command, reason, resolverName);
    }

    public static TaskMapTapVerificationResult defer(String observing, String command, String reason, String resolverName) {
        return defer(observing, "", "", "", command, reason, resolverName);
    }

    public static TaskMapTapVerificationResult defer(
            String observing,
            String judgingPrev,
            String judgePrevResult,
            String thinking,
            String command,
            String reason,
            String resolverName
    ) {
        return new TaskMapTapVerificationResult(
                DECISION_DEFER,
                command,
                -1,
                -1,
                observing,
                judgingPrev,
                judgePrevResult,
                thinking,
                reason,
                resolverName
        );
    }

    public static TaskMapTapVerificationResult error(String reason, String resolverName) {
        return new TaskMapTapVerificationResult(DECISION_ERROR, "", -1, -1, "", "", "", "", reason, resolverName);
    }

    public boolean isPrevious() {
        return DECISION_PREVIOUS.equals(decision);
    }

    public boolean isCurrent() {
        return DECISION_CURRENT.equals(decision);
    }

    public boolean isDefer() {
        return DECISION_DEFER.equals(decision);
    }

    public boolean isError() {
        return DECISION_ERROR.equals(decision);
    }

    public boolean hasTapPoint() {
        return tapX >= 0 && tapY >= 0;
    }

    public static String normalizeDecision(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (DECISION_PREVIOUS.equals(s)
                || DECISION_CURRENT.equals(s)
                || DECISION_DEFER.equals(s)
                || DECISION_ERROR.equals(s)) {
            return s;
        }
        return DECISION_ERROR;
    }

    public static TaskMapTapVerificationResult fromJsonDecision(String rawDecision, String command, String reason, String resolverName) {
        return fromJsonDecision(rawDecision, command, "", "", "", "", reason, resolverName);
    }

    public static TaskMapTapVerificationResult fromJsonDecision(
            String rawDecision,
            String command,
            String observing,
            String judgingPrev,
            String judgePrevResult,
            String thinking,
            String reason,
            String resolverName
    ) {
        String decision = normalizeDecision(rawDecision);
        if (DECISION_ERROR.equals(decision)) {
            return error("unsupported_decision:" + (rawDecision != null ? rawDecision : ""), resolverName);
        }
        if (DECISION_DEFER.equals(decision)) {
            String deferCommand = command != null ? command.trim() : "";
            if (!deferCommand.isEmpty()) {
                return error("invalid_command:" + deferCommand, resolverName);
            }
            return defer(observing, judgingPrev, judgePrevResult, thinking, "", reason, resolverName);
        }
        ParsedTapCommand parsed = parseTapCommand(command);
        if (parsed == null) {
            return error("invalid_command:" + (command != null ? command : ""), resolverName);
        }
        if (!isNormalizedCoordinate(parsed.x) || !isNormalizedCoordinate(parsed.y)) {
            return error("command_coordinates_out_of_range:" + (command != null ? command : ""), resolverName);
        }
        return DECISION_PREVIOUS.equals(decision)
                ? previous(parsed.x, parsed.y, observing, judgingPrev, judgePrevResult, thinking, reason, resolverName)
                : current(parsed.x, parsed.y, observing, judgingPrev, judgePrevResult, thinking, reason, resolverName);
    }

    private static ParsedTapCommand parseTapCommand(String command) {
        String cmd = command != null ? command.trim() : "";
        if (cmd.isEmpty()) {
            return null;
        }
        try {
            List<VisionCommandParser.Instruction> instructions = VisionCommandParser.parseInstructions(cmd, 1);
            if (instructions.size() != 1) {
                return null;
            }
            VisionCommandParser.Instruction ins = instructions.get(0);
            if (!"TAP".equalsIgnoreCase(ins.op) || ins.args.size() != 2) {
                return null;
            }
            int x = Integer.parseInt(ins.args.get(0).trim());
            int y = Integer.parseInt(ins.args.get(1).trim());
            return new ParsedTapCommand(x, y);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isNormalizedCoordinate(int v) {
        return v >= 0 && v <= 1000;
    }

    private static final class ParsedTapCommand {
        final int x;
        final int y;

        ParsedTapCommand(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
