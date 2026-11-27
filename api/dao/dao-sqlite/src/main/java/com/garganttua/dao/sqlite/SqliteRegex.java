package com.garganttua.dao.sqlite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.sql.SqlCondition;
import com.garganttua.dao.sql.SqlFragment;
import com.garganttua.dao.sql.SqlOperand;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlTypes;

/**
 * {@code $regex} on SQLite: the pattern runs on the JVM, in {@link #FUNCTION}, registered on every connection.
 *
 * <p>
 * MongoDB runs PCRE. {@code java.util.regex} shares its syntax — possessive quantifiers, atomic groups, look-arounds,
 * back-references, inline flags scoped as in PCRE — and is given PCRE's meaning where the defaults differ:
 * {@link Pattern#UNIX_LINES} so that only {@code \n} ends a line for {@code .}, {@code $} and {@code (?m)} (PCRE's
 * default newline), and {@link Pattern#UNICODE_CASE} so that {@code (?i)} folds non-ASCII letters as MongoDB does in
 * UTF-8 mode; {@code \w}, {@code \d}, {@code \s} and {@code \b} stay ASCII, as in MongoDB. A construct Java does not
 * know — recursion, conditionals — is refused when the filter is built, naming the field, never matched differently.
 * </p>
 */
final class SqliteRegex {

    /** {@code gg_regexp(value, pattern)}: 1 when the pattern finds a match in the value, NULL for a NULL value. */
    static final String FUNCTION = "gg_regexp";

    private static final int FLAGS = Pattern.UNIX_LINES | Pattern.UNICODE_CASE;
    private static final int CACHED_PATTERNS = 256;

    private static final Map<String, Pattern> PATTERNS = Collections.synchronizedMap(
            new LinkedHashMap<>(CACHED_PATTERNS, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > CACHED_PATTERNS;
                }
            });

    private SqliteRegex() {
    }

    /**
     * The predicate.
     *
     * @param o      the operand
     * @param c      the condition
     * @param domain the domain, for messages
     * @return the predicate
     * @throws ApiException when the condition has no pattern, or one Java cannot run
     */
    static SqlFragment on(SqlOperand o, SqlCondition c, String domain) throws ApiException {
        if (c.value() == null) {
            throw new ApiException("$regex filter on field '" + c.field() + "' requires a pattern");
        }
        String pattern = c.value().toString();
        try {
            pattern(pattern);
        } catch (PatternSyntaxException e) {
            throw new ApiException("$regex filter on field '" + c.field() + "' of domain '" + domain
                    + "': the pattern cannot be run by the SQLite DAO (" + e.getDescription() + " near index "
                    + e.getIndex() + ")", e);
        }
        if (o.isJsonPath()) {
            return inJson(o, pattern);
        }
        if (!holdsStrings(o)) {
            return SqlFragment.FALSE;
        }
        return SqlFragment.of(FUNCTION + "(").then(o.expr(false)).then(SqlFragment.of(", ?) = 1", pattern));
    }

    /**
     * Whether {@code pattern} finds a match in {@code value}.
     *
     * @param value   the text
     * @param pattern the PCRE pattern
     * @return whether it matches
     */
    static boolean find(String value, String pattern) {
        return pattern(pattern).matcher(value).find();
    }

    private static Pattern pattern(String pattern) {
        Pattern compiled = PATTERNS.get(pattern);
        if (compiled == null) {
            compiled = Pattern.compile(pattern, FLAGS);
            PATTERNS.put(pattern, compiled);
        }
        return compiled;
    }

    private static boolean holdsStrings(SqlOperand o) {
        SqlColumnKind kind = o.column().kind();
        return (kind == SqlColumnKind.SCALAR || kind == SqlColumnKind.ID) && SqlTypes.TEXT.equals(o.column().sqlType());
    }

    /** A string at the path, or a string element of an array at the path — MongoDB's implicit array traversal. */
    private static SqlFragment inJson(SqlOperand o, String pattern) {
        SqlFragment json = o.json();
        return SqlFragment.of("EXISTS (SELECT 1 FROM json_each(").then(json).then(") AS rx WHERE json_type(")
                .then(json).then(") IN ('array', 'text') AND rx.type = 'text' AND " + FUNCTION + "(rx.value, ")
                .then(SqlFragment.of("?) = 1)", pattern));
    }
}
