package com.garganttua.dao.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;

/**
 * The PCRE-to-ARE translation, proved differentially: every pattern is run on a real PostgreSQL
 * (translated, under {@code COLLATE "C"}, as {@link PgRegex} runs it) and with {@code java.util.regex}
 * (as close to PCRE as the JDK gets: {@code UNIX_LINES} for PCRE's LF-only newlines,
 * {@code UNICODE_CASE} for its UTF-mode case folding), on subjects chosen to separate the dialects —
 * trailing and inner newlines, word boundaries, accented capitals.
 */
@DisplayName("PgPcreTranslator")
class PgPcreTranslatorTest {

    private static final List<String> SUBJECTS = List.of("alpha", "Alpha", "ALPHA", "gamma\n", "line1\nline2",
            "the cat sat", "concatenate", "café", "CAFÉ", "Évian", "évian", "a+b", "B.200", "Bx200", "A-100", "abc",
            "a.b", "tab\there", "", "x\n\n", "Kelvin", "Kelvin", "ΣΙΣΥΦΟΣ", "σισυφος", "aaa", "ab", "a]b",
            "a-b", "a\\b", "{x}", "é́");

    private static final List<String> PATTERNS = List.of("^al", "(?i)^al", "^al(?i)PHA", "^gamma$", "a$", "\\Z",
            "line1.line2", "(?s)line1.line2", "(?m)^line2$", "(?m)1$", "\\bcat\\b", "\\Bcat", "cat\\B", "\\Aalpha\\z",
            "alpha\\Z", "\\d{3}$", "^\\w\\w\\w$", "^caf\\w$", "\\s", "\\S+", "^[A-B][^0-9]", "^(alpha|beta)$",
            "^B.200$", "^B\\.200$", "a\\+b", "a[.]b", "\\d++", "^a(?=l)", "(?<=a)b", "(?<!a)b", "(?i)^CAFÉ$",
            "(?i)évian", "(?i)[é]vian", "(?i)[a-c]", "(?i)^kelvin$", "(?i)^σισυφος$", "[^a]", "[\\]]", "[a\\-]b",
            "\\\\", "\\{x}", "x{2}", "a{1,2}b", "(a)\\1", "(?<n>a)\\k<n>", "(?:a|b)+?c", "\\x{e9}",
            "\\x41", "\\Qa+b\\E", "(?x) a \\+ b # comment", "\\h",
            "(?i:A)lpha", "(?i)A(?-i)lpha", "\\R", "[\\d.]+", "[\\D]", "a*+", "(?>a+)", "é",
            "(?i)É", "^$", "^", "\\K?a", ".", "(?s).");

    private static DataSource db;

    @BeforeAll
    static void setUp() {
        db = PgTestDatabase.freshDatabase();
    }

    @Test
    @DisplayName("every pattern selects on PostgreSQL exactly the subjects it selects with PCRE semantics")
    void differential() throws Exception {
        List<String> differences = new ArrayList<>();
        int matches = 0;
        try (Connection c = db.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT ? COLLATE \"C\" ~ ?")) {
            for (String pattern : PATTERNS) {
                if ("\\K?a".equals(pattern)) {
                    continue; // java.util.regex has no \K
                }
                String are = PgPcreTranslator.translate(pattern);
                Pattern pcre = Pattern.compile(pattern, Pattern.UNIX_LINES | Pattern.UNICODE_CASE);
                for (String subject : SUBJECTS) {
                    s.setString(1, subject);
                    s.setString(2, are);
                    try (ResultSet r = s.executeQuery()) {
                        r.next();
                        boolean expected = pcre.matcher(subject).find();
                        matches += expected ? 1 : 0;
                        if (r.getBoolean(1) != expected) {
                            differences.add(pattern + " -> " + are + " on '" + subject + "': PostgreSQL "
                                    + r.getBoolean(1) + ", PCRE " + expected);
                        }
                    }
                }
            }
        }
        assertTrue(differences.isEmpty(), () -> String.join("\n", differences));
        assertTrue(matches > PATTERNS.size() && matches < PATTERNS.size() * SUBJECTS.size() / 2,
                "the subjects must separate the patterns: " + matches + " matches");
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an invalid pattern is refused, as MongoDB refuses it")
        void invalid() {
            for (String pattern : List.of("(", ")", "[a", "*a", "a\\", "\\y", "(?z)", "a{2,1}b", "[z-a]")) {
                assertThrows(ApiException.class, () -> PgPcreTranslator.translate(pattern), pattern);
            }
        }

        @Test
        @DisplayName("a possessive quantifier or an atomic group followed by more pattern is refused, named")
        void possessive() {
            ApiException e = assertThrows(ApiException.class, () -> PgPcreTranslator.translate("a++a"));
            assertTrue(e.getMessage().contains("possessive"), e.getMessage());
            e = assertThrows(ApiException.class, () -> PgPcreTranslator.translate("(?>a+)a"));
            assertTrue(e.getMessage().contains("atomic"), e.getMessage());
        }

        @Test
        @DisplayName("what ARE cannot express is refused rather than approximated")
        void untranslatable() {
            for (String pattern : List.of("\\p{L}", "(a)(?1)", "(?R)", "(?(1)a|b)", "(?|a)", "(*SKIP)a", "(?i)(a)\\1",
                    "[\\p{L}]")) {
                ApiException e = assertThrows(ApiException.class, () -> PgPcreTranslator.translate(pattern), pattern);
                assertTrue(e.getMessage().contains("cannot reproduce exactly"), e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("constructs java.util.regex spells differently: POSIX classes, \\N, a literal brace")
    void pcreOnly() throws Exception {
        assertEquals("[0-9]{3}", PgPcreTranslator.translate("[[:digit:]]{3}"));
        assertEquals("[^\\u000A][^\\n]", PgPcreTranslator.translate("\\N."));
        assertEquals("\\{x\\}", PgPcreTranslator.translate("{x}"));
        assertEquals("ab", PgPcreTranslator.translate("a(?#note)b"));
        try (Connection c = db.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT ? COLLATE \"C\" ~ ?")) {
            s.setString(1, "ab1");
            s.setString(2, PgPcreTranslator.translate("^[[:^alpha:]]"));
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next() && !r.getBoolean(1), "[[:^alpha:]] must not match a letter");
            }
        }
    }

    @Test
    @DisplayName("the translation of common patterns stays readable")
    void readable() {
        assertEquals("\\^al", PgPcreTranslator.translate("\\^al"));
        assertEquals("^al[^\\n]", PgPcreTranslator.translate("^al."));
        assertEquals("[Aa]b", PgPcreTranslator.translate("(?i)a(?-i)b"));
    }
}
