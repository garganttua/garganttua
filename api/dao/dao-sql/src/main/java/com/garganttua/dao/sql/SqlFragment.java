package com.garganttua.dao.sql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A piece of SQL together with the bind values of its placeholders, in placeholder order.
 *
 * <p>
 * The filter translator assembles a {@code WHERE} clause out of many small fragments, and a
 * fragment's values must stay glued to its text: if text and values were collected separately, one
 * reordering (a {@code NOT} wrapped around a sub-expression, a {@code CASE} that repeats an
 * expression) would shift every later value onto the wrong placeholder — silently, since the types
 * often agree. Combining fragments only through this record makes that impossible.
 * </p>
 *
 * @param text   the SQL text, with {@code ?} placeholders
 * @param params the values of those placeholders, in order (nulls allowed)
 */
public record SqlFragment(String text, List<Object> params) {

    public static final SqlFragment TRUE = new SqlFragment("TRUE", List.of());
    public static final SqlFragment FALSE = new SqlFragment("FALSE", List.of());

    public SqlFragment {
        params = Collections.unmodifiableList(new ArrayList<>(params));
    }

    /** {@return a fragment of text and values} */
    public static SqlFragment of(String text, Object... params) {
        return new SqlFragment(text, Arrays.asList(params));
    }

    /** {@return this fragment between a prefix and a suffix, values unchanged} */
    public SqlFragment wrap(String prefix, String suffix) {
        return new SqlFragment(prefix + text + suffix, params);
    }

    /** {@return this fragment followed by another} */
    public SqlFragment then(SqlFragment next) {
        return join("", List.of(this, next));
    }

    /** {@return this fragment followed by literal text} */
    public SqlFragment then(String next) {
        return new SqlFragment(text + next, params);
    }

    /**
     * {@return the negation of a predicate that may be NULL, as MongoDB negates: a row where the
     * predicate is unknown (NULL, a missing value) is kept} Constants fold.
     */
    public static SqlFragment not(SqlFragment predicate) {
        if (TRUE.equals(predicate)) {
            return FALSE;
        }
        if (FALSE.equals(predicate)) {
            return TRUE;
        }
        return predicate.wrap("NOT COALESCE((", "), FALSE)");
    }

    /** {@return the disjunction of predicates, constants folded; FALSE when there are none} */
    public static SqlFragment any(List<SqlFragment> predicates) {
        List<SqlFragment> kept = new ArrayList<>();
        for (SqlFragment p : predicates) {
            if (TRUE.equals(p)) {
                return TRUE;
            }
            if (!FALSE.equals(p)) {
                kept.add(p);
            }
        }
        if (kept.isEmpty()) {
            return FALSE;
        }
        return kept.size() == 1 ? kept.get(0) : join(" OR ", kept).wrap("(", ")");
    }

    /** {@return the conjunction of predicates, constants folded; TRUE when there are none} */
    public static SqlFragment all(List<SqlFragment> predicates) {
        List<SqlFragment> kept = new ArrayList<>();
        for (SqlFragment p : predicates) {
            if (FALSE.equals(p)) {
                return FALSE;
            }
            if (!TRUE.equals(p)) {
                kept.add(p);
            }
        }
        if (kept.isEmpty()) {
            return TRUE;
        }
        return kept.size() == 1 ? kept.get(0) : join(" AND ", kept).wrap("(", ")");
    }

    /** {@return the fragments joined by a separator, values concatenated in the same order} */
    public static SqlFragment join(String separator, List<SqlFragment> parts) {
        StringBuilder text = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                text.append(separator);
            }
            text.append(parts.get(i).text());
            params.addAll(parts.get(i).params());
        }
        return new SqlFragment(text.toString(), params);
    }
}
