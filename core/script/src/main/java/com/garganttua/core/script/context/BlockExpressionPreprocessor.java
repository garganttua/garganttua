package com.garganttua.core.script.context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-processes script source text to extract block expressions before ANTLR4 parsing.
 * <p>
 * A block expression is a parenthesized group of statements used as a function argument:
 * <pre>
 * if(equals(@mode, "full"), (
 *     entities &lt;- doInjection(@0, @entities)
 *     entities &lt;- runAfterGet(@injectedEntities, @0)
 * ))
 * </pre>
 * <p>
 * Block expressions are detected by finding {@code (} followed by a newline, where the
 * {@code (} is preceded by {@code ,} or {@code (} on the same line (indicating it's a
 * function argument, not a statement group).
 * <p>
 * Each block is extracted, replaced with a {@code @__blkN} variable reference, and stored
 * for separate compilation.
 */
class BlockExpressionPreprocessor {

    private static final AtomicInteger BLOCK_COUNTER = new AtomicInteger(0);

    private final Map<String, String> blockSources = new LinkedHashMap<>();

    /**
     * Pre-processes the source, extracting block expressions.
     *
     * @param source the script source text
     * @return the processed source with blocks replaced by variable references
     */
    String preprocess(String source) {
        StringBuilder result = new StringBuilder();
        int len = source.length();
        int i = 0;

        while (i < len) {
            char c = source.charAt(i);

            int skipEnd = skipNonCode(source, i, c);
            if (skipEnd > i) {
                result.append(source, i, skipEnd);
                i = skipEnd;
                continue;
            }

            // Check for block expression start
            if (c == '(' && isBlockStart(source, i)) {
                int matchingParen = findMatchingParen(source, i);
                if (matchingParen > 0) {
                    i = extractBlock(source, result, i, matchingParen);
                    continue;
                }
            }

            result.append(c);
            i++;
        }

        return result.toString();
    }

    /**
     * @return the index just past a skippable non-code span (string literal or comment)
     *         starting at {@code i}, or {@code i} itself when nothing is skippable here
     */
    // Char-by-char scanner: comparing scanned chars to literals ('"', '/', '#', ...) is the
    // intrinsic shape of a tokenizer, not a magic-number smell.
    @SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
    private static int skipNonCode(String source, int i, char c) {
        int len = source.length();
        if (c == '"') {
            return skipString(source, i);
        }
        if (c == '/' && i + 1 < len && source.charAt(i + 1) == '/') {
            return skipLineComment(source, i);
        }
        if (c == '#' && (i == 0 || source.charAt(i - 1) == '\n')) {
            return skipLineComment(source, i);
        }
        if (c == '/' && i + 1 < len && source.charAt(i + 1) == '*') {
            return skipBlockComment(source, i);
        }
        return i;
    }

    /**
     * Extracts the parenthesized block starting at {@code start} (matching {@code matchingParen}),
     * recursively preprocesses it, stores it, and appends its {@code @__blkN} reference.
     *
     * @return the index just past the matching closing parenthesis
     */
    private int extractBlock(String source, StringBuilder result, int start, int matchingParen) {
        String blockContent = stripBlockContent(source.substring(start + 1, matchingParen));
        BlockExpressionPreprocessor innerPreprocessor = new BlockExpressionPreprocessor();
        String processedBlock = innerPreprocessor.preprocess(blockContent);
        blockSources.putAll(innerPreprocessor.getBlockSources());
        String blockName = "__blk" + BLOCK_COUNTER.getAndIncrement();
        blockSources.put(blockName, processedBlock);
        result.append("@").append(blockName);
        return matchingParen + 1;
    }

    Map<String, String> getBlockSources() {
        return blockSources;
    }

    /**
     * Checks if the {@code (} at position {@code pos} is the start of a block expression.
     * A block expression's {@code (} must be:
     * <ul>
     *   <li>Followed by a newline (after optional whitespace)</li>
     *   <li>Preceded by {@code ,} or {@code (} on the same line (function argument context)</li>
     * </ul>
     */
    private boolean isBlockStart(String source, int pos) {
        int len = source.length();

        // Check that ( is followed by \n (after optional whitespace)
        int j = pos + 1;
        while (j < len && (source.charAt(j) == ' ' || source.charAt(j) == '\t')) {
            j++;
        }
        if (j >= len || source.charAt(j) != '\n') {
            return false;
        }

        // Check that ( is preceded by , or ( on the same line (function argument context)
        int k = pos - 1;
        while (k >= 0 && (source.charAt(k) == ' ' || source.charAt(k) == '\t')) {
            k--;
        }
        if (k < 0) {
            return false; // ( at start of source
        }
        char prev = source.charAt(k);
        return prev == ',' || prev == '(' || prev == '>';
    }

    /**
     * Finds the position of the matching closing {@code )} for the {@code (} at position {@code pos}.
     * Tracks parenthesis depth and skips strings and comments.
     *
     * @return the position of the matching {@code )}, or -1 if not found
     */
    @SuppressWarnings("PMD.AvoidLiteralsInIfCondition") // tokenizer char comparisons
    private int findMatchingParen(String source, int pos) {
        int len = source.length();
        int depth = 1;
        int i = pos + 1;

        while (i < len && depth > 0) {
            char c = source.charAt(i);

            if (c == '"') {
                i = skipString(source, i);
                continue;
            }
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i);
                continue;
            }
            if (c == '#' && (i == 0 || source.charAt(i - 1) == '\n')) {
                i = skipLineComment(source, i);
                continue;
            }
            if (c == '/' && i + 1 < len && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i);
                continue;
            }

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    @SuppressWarnings("PMD.AvoidLiteralsInIfCondition") // tokenizer char comparisons
    private static int skipString(String source, int pos) {
        int i = pos + 1;
        int len = source.length();
        while (i < len) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2; // skip escaped character
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return len;
    }

    private static int skipLineComment(String source, int pos) {
        int nl = source.indexOf('\n', pos);
        return nl < 0 ? source.length() : nl;
    }

    private static int skipBlockComment(String source, int pos) {
        int end = source.indexOf("*/", pos + 2);
        return end < 0 ? source.length() : end + 2;
    }

    private static String stripBlockContent(String content) {
        // Remove leading newlines and trailing newlines/whitespace
        int start = 0;
        while (start < content.length() && (content.charAt(start) == '\n' || content.charAt(start) == '\r')) {
            start++;
        }
        int end = content.length();
        while (end > start && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r'
                || content.charAt(end - 1) == ' ' || content.charAt(end - 1) == '\t')) {
            end--;
        }
        return content.substring(start, end);
    }
}
