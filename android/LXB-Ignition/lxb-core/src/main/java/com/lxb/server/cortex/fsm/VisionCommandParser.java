package com.lxb.server.cortex.fsm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless parser utilities for VISION_ACT command extraction and DSL parsing.
 */
public final class VisionCommandParser {

    public static final class Instruction {
        public final String op;
        public final List<String> args;
        public final String raw;

        public Instruction(String op, List<String> args, String raw) {
            this.op = op;
            this.args = args;
            this.raw = raw;
        }
    }

    public static final class ExtractResult {
        public final String commandText;
        public final Map<String, Object> structured;

        public ExtractResult(String commandText, Map<String, Object> structured) {
            this.commandText = commandText != null ? commandText : "";
            this.structured = structured != null ? structured : new LinkedHashMap<String, Object>();
        }
    }

    public static final class InstructionError extends Exception {
        public InstructionError(String msg) {
            super(msg);
        }
    }

    private static final Map<String, int[]> INSTRUCTION_ARITY = new LinkedHashMap<String, int[]>();

    static {
        INSTRUCTION_ARITY.put("SET_APP", new int[]{1, 1});
        INSTRUCTION_ARITY.put("ROUTE", new int[]{2, 2});
        INSTRUCTION_ARITY.put("TAP", new int[]{2, 2});
        INSTRUCTION_ARITY.put("SWIPE", new int[]{5, 5});
        INSTRUCTION_ARITY.put("INPUT", new int[]{1, 1});
        INSTRUCTION_ARITY.put("WAIT", new int[]{1, 1});
        INSTRUCTION_ARITY.put("BACK", new int[]{0, 0});
        INSTRUCTION_ARITY.put("DONE", new int[]{0, 9999});
        INSTRUCTION_ARITY.put("FAIL", new int[]{1, 9999});
    }

    private VisionCommandParser() {
    }

    public static List<Instruction> parseInstructions(String text, int maxCommands) throws InstructionError {
        String[] lines = (text != null ? text : "").split("\\r?\\n");
        List<String> nonEmpty = new ArrayList<String>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                nonEmpty.add(trimmed);
            }
        }
        if (nonEmpty.isEmpty()) {
            throw new InstructionError("empty instruction output");
        }
        if (nonEmpty.size() > maxCommands) {
            throw new InstructionError("too many instructions: " + nonEmpty.size() + " > " + maxCommands);
        }

        List<Instruction> out = new ArrayList<Instruction>();
        for (String line : nonEmpty) {
            // Special-case DONE: treat everything after op as raw summary text.
            Instruction done = parseDoneInstruction(line);
            if (done != null) {
                validateArity(done.op, done.args);
                out.add(done);
                continue;
            }

            List<String> parts = shellSplit(line);
            if (parts.isEmpty()) {
                continue;
            }
            String op = parts.get(0).trim().toUpperCase();
            List<String> args = parts.subList(1, parts.size());
            validateArity(op, args);
            out.add(new Instruction(op, new ArrayList<String>(args), line));
        }
        if (out.isEmpty()) {
            throw new InstructionError("no valid instructions parsed");
        }
        return out;
    }

    private static Instruction parseDoneInstruction(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int firstWs = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                firstWs = i;
                break;
            }
        }

        String opToken = firstWs >= 0 ? trimmed.substring(0, firstWs) : trimmed;
        if (!"DONE".equalsIgnoreCase(opToken)) {
            return null;
        }

        List<String> args = new ArrayList<String>();
        if (firstWs >= 0) {
            String tail = trimmed.substring(firstWs + 1).trim();
            if (!tail.isEmpty()) {
                args.add(tail);
            }
        }
        return new Instruction("DONE", args, line);
    }

    public static void validateAllowed(List<Instruction> instructions, Set<String> allowedOps) throws InstructionError {
        Set<String> allowed = new java.util.HashSet<String>();
        for (String op : allowedOps) {
            allowed.add(op.toUpperCase());
        }
        for (Instruction ins : instructions) {
            if (!allowed.contains(ins.op)) {
                throw new InstructionError("op not allowed in this state: " + ins.op);
            }
        }
    }

    public static String extractTagText(String text, String tag) {
        if (text == null || text.isEmpty() || tag == null || tag.isEmpty()) {
            return "";
        }
        String safeTag = Pattern.quote(tag);
        String strict = "(?is)<\\s*" + safeTag + "\\s*>\\s*([\\s\\S]*?)\\s*</\\s*" + safeTag + "\\s*>";
        Matcher m = Pattern.compile(strict, Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        String openOnly = "(?is)<\\s*" + safeTag + "\\s*>\\s*([\\s\\S]*?)(?=<\\s*[A-Za-z_][A-Za-z0-9_]*\\s*>|$)";
        Matcher m2 = Pattern.compile(openOnly, Pattern.CASE_INSENSITIVE).matcher(text);
        if (m2.find()) {
            return m2.group(1).trim();
        }
        return "";
    }

    public static ExtractResult extractStructuredCommandForVision(String raw) {
        String text = raw != null ? raw.trim() : "";
        if (text.isEmpty()) {
            return new ExtractResult("", new LinkedHashMap<String, Object>());
        }

        String cmd = extractTagText(text, "command");
        if (cmd.isEmpty()) {
            return new ExtractResult(text, new LinkedHashMap<String, Object>());
        }

        String rootContent = extractTagText(text, "vision_analysis");
        if (rootContent.isEmpty()) {
            return new ExtractResult(cmd.trim(), new LinkedHashMap<String, Object>());
        }

        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("root", "vision_analysis");
        String[] names = new String[]{
                "page_state",
                "step_review",
                "reflection",
                "next_step_reasoning",
                "completion_gate",
                "done_confirm",
                "lesson"
        };
        for (String name : names) {
            String fv = extractTagText(rootContent, name);
            if (!fv.isEmpty()) {
                fields.put(name, fv.trim());
            }
        }
        return new ExtractResult(cmd.trim(), fields);
    }

    private static void validateArity(String op, List<String> args) throws InstructionError {
        int[] range = INSTRUCTION_ARITY.get(op);
        if (range == null) {
            throw new InstructionError("unknown instruction op: " + op);
        }
        int n = args.size();
        int min = range[0];
        int max = range[1];
        if (n < min || n > max) {
            String expected = (min == max) ? String.valueOf(min) : (min + ".." + max);
            throw new InstructionError(op + " expects " + expected + " args, got " + n);
        }
    }

    private static List<String> shellSplit(String line) throws InstructionError {
        List<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'' || c == '"') {
                    inQuotes = true;
                    quoteChar = c;
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }
        if (inQuotes) {
            throw new InstructionError("invalid instruction quoting: " + line);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }
}
