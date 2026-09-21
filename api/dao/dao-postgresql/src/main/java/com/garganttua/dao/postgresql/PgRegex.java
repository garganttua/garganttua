package com.garganttua.dao.postgresql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgTypes;

/**
 * The predicate of a {@code $regex} comparison, with MongoDB's meaning.
 *
 * <p>
 * MongoDB runs the pattern with PCRE and applies it to STRING values only: a number, a boolean, a
 * date, an embedded document or a DBRef never matches, and neither does a missing field — no error
 * either way. So the pattern is first translated to an equivalent PostgreSQL regular expression by
 * {@link PgPcreTranslator} (which refuses what it cannot reproduce exactly, and refuses an invalid
 * pattern as MongoDB does, whatever the field), then:
 * </p>
 * <ul>
 * <li>a TEXT column (the uuid, a string, an enum, a flattened POJO string) is matched with
 * {@code ~};</li>
 * <li>a path inside a JSONB document is matched only where the value there is a JSON string — or,
 * when it is an array, where one of its string elements matches, as MongoDB does;</li>
 * <li>any other column — numbers, booleans, dates, geometries, composition references — matches
 * nothing: {@code FALSE}, which a surrounding {@code $nor} turns into a kept row.</li>
 * </ul>
 * <p>
 * On a collection, {@link PgElementPredicates} wraps this predicate in an {@code EXISTS}: the owner
 * matches when any element matches.
 * </p>
 *
 * <p>
 * <b>Locale.</b> The comparison is made under {@code COLLATE "C"}, and the translated pattern spells
 * every class and every case-insensitive letter as explicit code points, so the answer does not
 * depend on the database's {@code LC_CTYPE} or {@code LC_COLLATE}: {@code (?i)É} matches {@code é}
 * even on a {@code C}-locale database, and {@code \w} stays ASCII even on a {@code fr_FR.UTF-8} one —
 * both as in MongoDB. The case variants come from Java's Unicode tables; they agree with PCRE2's
 * simple case folding for every letter except the Turkish dotted and dotless i, excluded on both.
 * </p>
 */
final class PgRegex {

    private static final String COLLATE_C = " COLLATE \"C\"";

    private PgRegex() {
    }

    /**
     * The predicate of {@code $regex} on an operand.
     *
     * @param o      the operand
     * @param c      the comparison; its value is the PCRE pattern (any value is used as its text)
     * @param domain the domain name, for messages
     * @return the predicate
     * @throws ApiException when the pattern is missing, invalid, or not translatable exactly
     */
    static PgSql on(PgOperand o, PgCondition c, String domain) {
        if (c.value() == null) {
            throw new ApiException("$regex filter on field '" + c.field() + "' requires a pattern");
        }
        String pattern = translate(c.value().toString(), c.field(), domain);
        if (o.isJsonPath()) {
            return inJson(o, pattern);
        }
        if (!holdsStrings(o)) {
            return PgSql.FALSE;
        }
        return o.expr(false).then(COLLATE_C + " ~ ?").then(PgSql.of("", pattern));
    }

    private static String translate(String pattern, String field, String domain) {
        try {
            return PgPcreTranslator.translate(pattern);
        } catch (ApiException e) {
            throw new ApiException("$regex filter on field '" + field + "' of domain '" + domain + "': "
                    + e.getMessage(), e);
        }
    }

    /** Only a column of strings can match: never a number, a date, a document or a reference. */
    private static boolean holdsStrings(PgOperand o) {
        PgColumnKind kind = o.column().kind();
        return (kind == PgColumnKind.SCALAR || kind == PgColumnKind.ID) && PgTypes.TEXT.equals(o.column().sqlType());
    }

    /** The string at a JSON path, or any string element of the array there. */
    private static PgSql inJson(PgOperand o, String pattern) {
        String marks = String.join(", ", Collections.nCopies(o.jsonPath().size(), "?"));
        List<Object> params = new ArrayList<>(o.jsonPath());
        params.add(pattern);
        return new PgSql("EXISTS (SELECT 1 FROM jsonb_path_query(jsonb_extract_path(" + o.qualifiedColumn() + ", "
                + marks + "), 'lax $ ? (@.type() == \"string\")') AS rx(v) WHERE (rx.v #>> '{}')" + COLLATE_C
                + " ~ ?)", params);
    }
}
