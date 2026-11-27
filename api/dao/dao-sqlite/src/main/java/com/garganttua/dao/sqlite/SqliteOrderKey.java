package com.garganttua.dao.sqlite;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The sort key of a value, as text that SQLite's binary comparison orders the way BSON orders the value.
 *
 * <p>
 * PostgreSQL sorts a whole document or array natively, as a row or an array of rows. SQLite cannot sort on a row
 * value, so the SQLite dialect builds the same order out of text: every component is encoded here into upper-case
 * hexadecimal — whose code points order as the bytes they spell — and the components are concatenated. Each encoding
 * is order-preserving and self-delimiting (fixed width, or terminated), so the concatenation of a record compares
 * component by component, and a sequence that is a prefix of another sorts before it — exactly the BSON order of an
 * array or a sub-document.
 * </p>
 *
 * <table>
 * <caption>Encodings</caption>
 * <tr><th>Value</th><th>Key</th></tr>
 * <tr><td>NaN</td><td>{@code 0F} — below every number, as in BSON</td></tr>
 * <tr><td>a number</td><td>{@code 10}, the order-preserving bits of its nearest double, then the exact remainder of
 * an integer beyond 2<sup>53</sup>: integers and doubles compare by value, as BSON compares them</td></tr>
 * <tr><td>a text</td><td>{@code 20}, its UTF-8 bytes with {@code 00} escaped as {@code 00FF}, then {@code 0000}: byte
 * order, a prefix first</td></tr>
 * <tr><td>a blob</td><td>{@code 30}, its length on 4 bytes, then its bytes: BSON compares binaries by length
 * first</td></tr>
 * <tr><td>an integer component (a rank, a position)</td><td>{@code 40} and its order-preserving 8 bytes</td></tr>
 * <tr><td>an absent component</td><td>{@code FF} — above every value</td></tr>
 * </table>
 */
public final class SqliteOrderKey {

    /** The key of an absent component. */
    public static final String ABSENT = "FF";
    /** The tag of NaN. */
    static final String NAN = "0F";
    /** The end of a sequence. */
    public static final String END = "00";
    /** The start of an element of a sequence. */
    public static final String ELEMENT = "01";

    private static final String NUMBER = "10";
    private static final String TEXT = "20";
    private static final String BLOB = "30";
    private static final String INTEGER = "40";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int BYTE_MASK = 0xFF;
    private static final int NIBBLE = 4;
    private static final int LOW_NIBBLE = 0x0F;
    private static final int LONG_BYTES = 8;
    private static final int INT_BYTES = 4;

    private SqliteOrderKey() {
    }

    /**
     * The key of a number.
     *
     * @param value the number, a {@link Long} or a {@link Double}
     * @return its key
     */
    public static String number(Number value) {
        if (value instanceof Long || value instanceof Integer) {
            long exact = value.longValue();
            double nearest = exact;
            long remainder = Double.isInfinite(nearest) || nearest >= 0x1p63 ? 0 : exact - (long) nearest;
            return NUMBER + sortable(nearest) + signed(remainder);
        }
        double d = value.doubleValue();
        if (Double.isNaN(d)) {
            return NAN;
        }
        return NUMBER + sortable(d) + signed(0);
    }

    /**
     * The key of a text.
     *
     * @param text the text
     * @return its key
     */
    public static String text(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(TEXT.length() + bytes.length * 2 + NIBBLE);
        out.append(TEXT);
        for (byte b : bytes) {
            hex(out, b & BYTE_MASK);
            if (b == 0) {
                out.append(ABSENT);
            }
        }
        return out.append(END).append(END).toString();
    }

    /**
     * The key of a blob.
     *
     * @param bytes the bytes
     * @return its key
     */
    public static String blob(byte[] bytes) {
        StringBuilder out = new StringBuilder(BLOB.length() + (INT_BYTES + bytes.length) * 2);
        out.append(BLOB);
        for (int shift = (INT_BYTES - 1) * Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            hex(out, (bytes.length >>> shift) & BYTE_MASK);
        }
        for (byte b : bytes) {
            hex(out, b & BYTE_MASK);
        }
        return out.toString();
    }

    /**
     * The key of an integer component of a record — a BSON type rank, a field position.
     *
     * @param value the integer
     * @return its key
     */
    public static String integer(long value) {
        return INTEGER + signed(value);
    }

    /** The 8 bytes of a double, ordered as the doubles are: −0 and 0 are one value, as in BSON. */
    private static String sortable(double value) {
        double d = value == 0 ? 0 : value;
        long bits = Double.doubleToLongBits(d);
        return unsigned(bits < 0 ? ~bits : bits ^ Long.MIN_VALUE);
    }

    private static String signed(long value) {
        return unsigned(value ^ Long.MIN_VALUE);
    }

    private static String unsigned(long value) {
        StringBuilder out = new StringBuilder(LONG_BYTES * 2);
        for (int shift = (LONG_BYTES - 1) * Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            hex(out, (int) (value >>> shift) & BYTE_MASK);
        }
        return out.toString();
    }

    private static void hex(StringBuilder out, int b) {
        out.append(HEX[b >>> NIBBLE]).append(HEX[b & LOW_NIBBLE]);
    }

    /** {@return whether {@code text} is a key this class produced} — upper-case hexadecimal, of even length. */
    static boolean isKey(String text) {
        if (text.length() % 2 != 0) {
            return false;
        }
        return text.equals(text.toUpperCase(Locale.ROOT)) && text.chars().allMatch(c -> Character.digit(c, 16) >= 0);
    }
}
