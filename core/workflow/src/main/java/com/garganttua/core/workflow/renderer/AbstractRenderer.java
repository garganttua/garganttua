package com.garganttua.core.workflow.renderer;

/**
 * Shared style constants and ANSI-aware text utilities for the workflow renderers.
 *
 * <p>Package-private base for {@link WorkflowRenderer} and {@link StageRenderer}; centralises the
 * ANSI colour palette and the padding/truncation helpers both need.
 */
// AbstractClassWithoutAbstractMethod: intentional shared-state base for the two renderers — not instantiable on its own.
@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
abstract class AbstractRenderer {

    // ANSI Color codes
    protected static final String RESET = "\u001B[0m";
    protected static final String BOLD = "\u001B[1m";
    protected static final String DIM = "\u001B[2m";
    protected static final String ITALIC = "\u001B[3m";
    protected static final String RED = "\u001B[31m";
    protected static final String GREEN = "\u001B[32m";
    protected static final String YELLOW = "\u001B[33m";
    protected static final String BLUE = "\u001B[34m";
    protected static final String MAGENTA = "\u001B[35m";
    protected static final String CYAN = "\u001B[36m";
    protected static final String WHITE = "\u001B[37m";
    protected static final String BG_BLUE = "\u001B[44m";

    // Repeated 4-space indent fragment in the render output (box chars are left inline:
    // they are pre-existing double-encoded literals that must keep their exact bytes).
    protected static final String INDENT = "    ";

    protected String pad(int length) {
        if (length <= 0) {
            return "";
        }
        return " ".repeat(length);
    }

    protected String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    protected String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }

    protected String formatValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + value + "\"";
        return value.toString();
    }

    protected String centerText(String text, int width) {
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text + " ".repeat(Math.max(0, width - padding - text.length()));
    }

    /** {@return {@code true} if the two bypass flows' stage ranges overlap and so cannot share a lane}. */
    protected boolean rangesOverlap(BypassFlow a, BypassFlow b) {
        return !(a.targetStage() <= b.sourceStage() || b.targetStage() <= a.sourceStage());
    }

}
