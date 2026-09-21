package com.garganttua.dao.postgresql;

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
record PgSql(String text, List<Object> params) {

    static final PgSql TRUE = new PgSql("TRUE", List.of());
    static final PgSql FALSE = new PgSql("FALSE", List.of());

    PgSql {
        params = Collections.unmodifiableList(new ArrayList<>(params));
    }

    /** {@return a fragment of text and values} */
    static PgSql of(String text, Object... params) {
        return new PgSql(text, Arrays.asList(params));
    }

    /** {@return this fragment between a prefix and a suffix, values unchanged} */
    PgSql wrap(String prefix, String suffix) {
        return new PgSql(prefix + text + suffix, params);
    }

    /** {@return this fragment followed by another} */
    PgSql then(PgSql next) {
        return join("", List.of(this, next));
    }

    /** {@return this fragment followed by literal text} */
    PgSql then(String next) {
        return new PgSql(text + next, params);
    }

    /**
     * {@return the negation of a predicate that may be NULL, as MongoDB negates: a row where the
     * predicate is unknown (NULL, a missing value) is kept} Constants fold.
     */
    static PgSql not(PgSql predicate) {
        if (TRUE.equals(predicate)) {
            return FALSE;
        }
        if (FALSE.equals(predicate)) {
            return TRUE;
        }
        return predicate.wrap("NOT COALESCE((", "), FALSE)");
    }

    /** {@return the disjunction of predicates, constants folded; FALSE when there are none} */
    static PgSql any(List<PgSql> predicates) {
        List<PgSql> kept = new ArrayList<>();
        for (PgSql p : predicates) {
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
    static PgSql all(List<PgSql> predicates) {
        List<PgSql> kept = new ArrayList<>();
        for (PgSql p : predicates) {
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
    static PgSql join(String separator, List<PgSql> parts) {
        StringBuilder text = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                text.append(separator);
            }
            text.append(parts.get(i).text());
            params.addAll(parts.get(i).params());
        }
        return new PgSql(text.toString(), params);
    }
}
