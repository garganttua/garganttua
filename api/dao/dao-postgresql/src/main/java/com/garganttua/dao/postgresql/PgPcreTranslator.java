package com.garganttua.dao.postgresql;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.garganttua.api.commons.ApiException;

/**
 * Rewrites a PCRE pattern — what MongoDB's {@code $regex} runs — into a PostgreSQL ARE (advanced
 * regular expression) that matches exactly the same strings, or refuses it.
 *
 * <p>
 * The two dialects share most syntax and differ where it hurts silently: in ARE, {@code \b} is a
 * backspace, {@code $} does not match before a final newline, {@code .} matches a newline, and
 * {@code \w}, {@code [[:alpha:]]} and {@code (?i)} follow the database's locale. So every construct
 * is re-spelled with PCRE's meaning, in terms that do not depend on the database:
 * </p>
 * <ul>
 * <li>{@code .} becomes {@code [^\n]} (or stays {@code .} under {@code (?s)});</li>
 * <li>{@code $} and {@code \Z} become {@code (?=\n?$)}; {@code \z} becomes {@code $}; {@code \A}
 * and {@code \G} become {@code ^}; {@code (?m)} anchors become lookarounds on {@code \n};</li>
 * <li>{@code \b} / {@code \B} become lookarounds on the ASCII word characters;</li>
 * <li>classes become explicit code-point brackets ({@link PgPcreClass}), and case-insensitive
 * letters become brackets of their case variants ({@link PgCaseFold}) — so {@code (?i)} works
 * anywhere in the pattern, with PCRE's scoping, and folds non-ASCII letters whatever the database's
 * {@code LC_CTYPE};</li>
 * <li>a possessive quantifier or an atomic group becomes its plain form when nothing follows it,
 * where it provably makes no difference to WHETHER a match exists — the only thing
 * {@code $regex} asks.</li>
 * </ul>
 *
 * <p>
 * What ARE cannot express exactly is refused with an {@link ApiException} naming the construct —
 * a possessive quantifier or an atomic group followed by more pattern, recursion, conditionals,
 * Unicode properties, back-references under {@code (?i)} — never replaced by something that matches
 * different strings.
 * </p>
 */
// A PCRE reader: the character literals it tests ('\\', '{', ')', 'Q', ...) ARE the grammar being parsed,
// and its small integers are bounds (group 1, range lengths); naming each would only hide the syntax.
// Its constants share a name with the method that uses them (accessor style).
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidLiteralsInIfCondition"})
final class PgPcreTranslator {

    private static final String WORD = "[0-9A-Z_a-z]";
    private static final String WORD_BOUNDARY = "(?:(?<!" + WORD + ")(?=" + WORD + ")|(?<=" + WORD + ")(?!" + WORD
            + "))";
    private static final String NOT_WORD_BOUNDARY = "(?:(?<=" + WORD + ")(?=" + WORD + ")|(?<!" + WORD + ")(?!"
            + WORD + "))";
    private static final String NEWLINE_SEQUENCE = "(?:\\r\\n|[\\n\\v\\f\\r\\u0085\\u2028\\u2029])";
    private static final Pattern BRACES = Pattern.compile("\\{(\\d+)(,(\\d*))?}");
    private static final int ARE_MAX_REPEAT = 255;

    /** PCRE's options, as far as the translation needs them. */
    private record Flags(boolean caseless, boolean multiline, boolean dotAll, boolean extended) {
    }

    /** An open group: the flags to restore at its end, and what kind of group it is. */
    private record Group(Flags saved, boolean atomic, boolean assertion) {
    }

    private final PgPcreCursor in;
    @SuppressWarnings("PMD.AvoidStringBufferField") // one translator per pattern, discarded once translated
    private final StringBuilder out = new StringBuilder();
    private final Deque<Group> groups = new ArrayDeque<>();
    private final Map<String, Integer> names = new HashMap<>();
    private Flags flags = new Flags(false, false, false, false);
    private int captures;
    private boolean repeatable;

    private PgPcreTranslator(String pattern) {
        this.in = new PgPcreCursor(pattern);
    }

    /**
     * Translates a pattern.
     *
     * @param pattern the PCRE pattern
     * @return the equivalent ARE pattern
     * @throws ApiException when the pattern is invalid, or uses a construct ARE cannot reproduce
     */
    static String translate(String pattern) {
        PgPcreTranslator translator = new PgPcreTranslator(pattern);
        while (!translator.in.atEnd()) {
            translator.step(translator.in.next());
        }
        if (!translator.groups.isEmpty()) {
            throw translator.in.invalid("a group is not closed by ')'");
        }
        return translator.out.toString();
    }

    private void step(int cp) {
        if (flags.extended() && skipExtended(cp)) {
            return;
        }
        switch (cp) {
            case '\\' -> escape(in.next());
            case '[' -> atom(PgPcreClass.parse(in).toAre(flags.caseless()));
            case '(' -> open();
            case ')' -> close();
            case '|' -> {
                out.append('|');
                repeatable = false;
            }
            case '.' -> atom(flags.dotAll() ? "." : "[^\\n]");
            case '^' -> assertion(flags.multiline() ? "(?:^|(?<=\\n)(?=.))" : "^");
            case '$' -> assertion(flags.multiline() ? "(?=\\n|$)" : "(?=\\n?$)");
            case '*', '+', '?' -> quantifier(Character.toString(cp));
            case '{' -> braces();
            default -> literal(cp);
        }
    }

    /** In {@code (?x)}, whitespace and {@code #} comments outside classes are not part of the pattern. */
    private boolean skipExtended(int cp) {
        if (cp == '#') {
            while (!in.atEnd() && in.next() != '\n') {
                // skip the comment
            }
            return true;
        }
        return cp == ' ' || cp >= '\t' && cp <= '\r';
    }

    private void escape(int c) {
        PgPcreClass cls = PgPcreClass.escape(c);
        if (cls != null) {
            atom(cls.toAre(false));
            return;
        }
        switch (c) {
            case 'b' -> assertion(WORD_BOUNDARY);
            case 'B' -> assertion(NOT_WORD_BOUNDARY);
            case 'A', 'G' -> assertion("^");
            case 'z' -> assertion("$");
            case 'Z' -> assertion("(?=\\n?$)");
            case 'R' -> atom(NEWLINE_SEQUENCE);
            case 'K', 'E' -> {
                // \K only moves the reported start of the match; \E ends a \Q that is not open
            }
            case 'Q' -> quoted();
            case 'g', 'k' -> backReference(reference(c));
            case 'p', 'P', 'X', 'C' -> throw in.untranslatable("\\" + Character.toString(c),
                    "Unicode properties, grapheme clusters and single code units have no ARE equivalent");
            default -> {
                if (c >= '1' && c <= '9') {
                    numbered(c);
                } else {
                    literal(in.character(c));
                }
            }
        }
    }

    private void quoted() {
        while (!in.atEnd() && !in.eat("\\E")) {
            literal(in.next());
        }
    }

    /** {@code \1}…: a back-reference, or an octal character code when that many groups do not exist. */
    private void numbered(int first) {
        String digits = Character.toString(first) + in.digits();
        int number = Integer.parseInt(digits);
        if (number < 10 || number <= captures) {
            backReference(number);
        } else if (digits.chars().allMatch(d -> d < '8')) {
            literal(Integer.parseInt(digits, 8));
        } else {
            throw in.invalid("\\" + digits + " refers to a group that does not exist");
        }
    }

    /** The group number of {@code \g{n}}, {@code \g{-n}}, {@code \g{name}}, {@code \k<name>}… */
    private int reference(int letter) {
        String ref;
        if (letter == 'g' && in.peek(0) != '{' && in.peek(0) != '<' && in.peek(0) != '\'') {
            String sign = in.eat("-") ? "-" : in.eat("+") ? "+" : "";
            ref = sign + in.digits();
        } else {
            int open = in.next();
            ref = in.until(open == '<' ? '>' : open == '{' ? '}' : '\'');
        }
        return groupNumber(ref);
    }

    private int groupNumber(String ref) {
        if (ref.matches("[+-]?\\d+")) {
            int n = Integer.parseInt(ref.startsWith("+") ? ref.substring(1) : ref);
            if (ref.startsWith("+")) {
                throw in.untranslatable("a forward relative back-reference", "ARE groups are numbered absolutely");
            }
            return n < 0 ? captures + 1 + n : n;
        }
        Integer number = names.get(ref);
        if (number == null) {
            throw in.invalid("no group is named '" + ref + "'");
        }
        return number;
    }

    private void backReference(int number) {
        if (number < 1) {
            throw in.invalid("a back-reference must name group 1 or above");
        }
        if (flags.caseless()) {
            throw in.untranslatable("a back-reference under (?i)",
                    "PostgreSQL would compare the referenced text case-sensitively or by its own locale");
        }
        out.append('\\').append(number);
        repeatable = true;
    }

    private void open() {
        if (in.eat("?#")) {
            in.until(')');
            return;
        }
        if (in.peek(0) == '*') {
            throw in.untranslatable("a backtracking control verb (*…)", "ARE has no backtracking verbs");
        }
        if (!in.eat("?")) {
            push(false, false, "(");
            captures++;
            return;
        }
        special();
    }

    /** Everything that starts with {@code (?}. */
    private void special() {
        if (in.eat(":")) {
            push(false, false, "(?:");
        } else if (in.eat("=") || in.eat("!")) {
            push(false, true, "(?" + (char) in.peek(-1));
        } else if (in.eat("<=") || in.eat("<!")) {
            push(false, true, "(?<" + (char) in.peek(-1));
        } else if (in.eat(">")) {
            push(true, false, "(?:");
        } else if (in.eat("P<") || in.eat("<") || in.eat("'")) {
            String name = in.until(in.peek(-1) == '\'' ? '\'' : '>');
            captures++;
            names.put(name, captures);
            push(false, false, "(");
        } else if (in.eat("P=")) {
            backReference(groupNumber(in.until(')')));
        } else if (in.peek(0) == '|' || in.peek(0) == '(' || in.peek(0) == 'R' || in.peek(0) == '&'
                || in.peek(0) == 'C' || in.eat("P>") || Character.isDigit(in.peek(0)) || in.peek(0) == '+'
                || in.peek(0) == '-' && Character.isDigit(in.peek(1))) {
            throw in.untranslatable("(?" + (char) in.peek(0) + "…)",
                    "recursion, subroutine calls, conditionals, branch reset and callouts do not exist in ARE");
        } else {
            options();
        }
    }

    /** {@code (?imsx-imsx)} for the rest of the group, or {@code (?imsx-imsx:…)} for a new group. */
    private void options() {
        Flags changed = flags;
        boolean on = true;
        while (true) {
            int c = in.next();
            switch (c) {
                case ')' -> {
                    flags = changed;
                    return;
                }
                case ':' -> {
                    push(false, false, "(?:");
                    flags = changed;
                    return;
                }
                case '-' -> {
                    on = false;
                }
                case '^' -> {
                    changed = new Flags(false, false, false, false);
                }
                default -> {
                    changed = option(changed, c, on);
                }
            }
        }
    }

    private Flags option(Flags f, int c, boolean on) {
        return switch (c) {
            case 'i' -> new Flags(on, f.multiline(), f.dotAll(), f.extended());
            case 'm' -> new Flags(f.caseless(), on, f.dotAll(), f.extended());
            case 's' -> new Flags(f.caseless(), f.multiline(), on, f.extended());
            case 'x' -> {
                if (in.peek(0) == 'x') {
                    throw in.untranslatable("the (?xx) option", "it is not supported by this translation");
                }
                yield new Flags(f.caseless(), f.multiline(), f.dotAll(), on);
            }
            case 'U' -> f; // swaps greediness: which text matches changes, whether a match exists does not
            case 'n', 'J' -> throw in.untranslatable("the (?" + (char) c + ") option",
                    "it changes group numbering, which ARE cannot follow");
            default -> throw in.invalid("(?" + Character.toString(c) + " is not a PCRE option");
        };
    }

    private void push(boolean atomic, boolean assertion, String opening) {
        groups.push(new Group(flags, atomic, assertion));
        out.append(opening);
        repeatable = false;
    }

    private void close() {
        if (groups.isEmpty()) {
            throw in.invalid("')' closes no group");
        }
        Group group = groups.pop();
        if (group.atomic() && !in.onlyClosersRemain()) {
            throw in.untranslatable("an atomic group (?>…) followed by more pattern",
                    "ARE always backtracks, and a match that needs the group to give back text would appear");
        }
        out.append(')');
        flags = group.saved();
        repeatable = !group.assertion();
    }

    private void braces() {
        Matcher m = BRACES.matcher("{" + in.rest());
        if (!m.lookingAt()) {
            literal('{');
            return;
        }
        String max = m.group(3) == null ? m.group(1) : m.group(3);
        int min = Integer.parseInt(m.group(1));
        if (!max.isEmpty() && Integer.parseInt(max) < min) {
            throw in.invalid("the repetition " + m.group() + " has its numbers out of order");
        }
        if (min > ARE_MAX_REPEAT || !max.isEmpty() && Integer.parseInt(max) > ARE_MAX_REPEAT) {
            throw in.untranslatable("a repetition count above " + ARE_MAX_REPEAT, "PostgreSQL's maximum");
        }
        in.skip(m.end() - 1);
        quantifier(m.group());
    }

    private void quantifier(String quantifier) {
        if (!repeatable) {
            throw in.invalid("the quantifier '" + quantifier + "' has nothing to repeat");
        }
        out.append(quantifier);
        repeatable = false;
        if (in.eat("+")) {
            if (!in.onlyClosersRemain()) {
                throw in.untranslatable("the possessive quantifier '" + quantifier + "+' followed by more pattern",
                        "ARE always backtracks, and a match that needs the quantifier to give back text would appear");
            }
        } else if (in.eat("?")) {
            out.append('?');
        }
    }

    private void assertion(String are) {
        out.append(are);
        repeatable = false;
    }

    private void atom(String are) {
        out.append(are);
        repeatable = true;
    }

    private void literal(int cp) {
        if (flags.caseless() && PgCaseFold.variants(cp).length > 1) {
            StringBuilder bracket = new StringBuilder("[");
            for (int variant : PgCaseFold.variants(cp)) {
                bracket.append(PgPcreClass.escape(variant, true));
            }
            atom(bracket.append(']').toString());
            return;
        }
        atom(PgPcreClass.escape(cp, false));
    }
}
