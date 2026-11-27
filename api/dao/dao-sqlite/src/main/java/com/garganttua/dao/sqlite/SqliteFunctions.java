package com.garganttua.dao.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.sqlite.Function;
import org.sqlite.SQLiteConnection;

/**
 * The functions the SQLite DAO's SQL calls, registered on each connection before its first use.
 *
 * <p>
 * SQLite runs inside the JVM: what its SQL cannot say — the BSON order of a value, a PCRE-like regular expression, an
 * English stem, a distance on the sphere — is a Java function the statement calls. A function is registered per
 * connection, so each physical connection is readied once (its foreign keys turned on, its functions registered),
 * and remembered weakly: a connection the pool drops is forgotten with it.
 * </p>
 *
 * <table>
 * <caption>Functions</caption>
 * <tr><th>Function</th><th>Value</th></tr>
 * <tr><td>{@code gg_okey(v)}</td><td>the {@link SqliteOrderKey} of a scalar: number, text or blob</td></tr>
 * <tr><td>{@code gg_okey_float(v)}</td><td>the same for a floating-point column, whose NaN is stored as the text
 * {@code 'NaN'}</td></tr>
 * <tr><td>{@code gg_opart(v)}</td><td>a component of a record: an integer encoded, a key kept, NULL as absent</td></tr>
 * <tr><td>{@code gg_real_text(v)}</td><td>Java's exact text of a double — the sign of -0.0, the infinities</td></tr>
 * <tr><td>{@code gg_regexp(v, p)}</td><td>{@link SqliteRegex}</td></tr>
 * <tr><td>{@code gg_text(d, s)}</td><td>{@link SqliteTextSearch}</td></tr>
 * <tr><td>{@code gg_geo_within(f, s)}, {@code gg_geo_intersects(f, s)}, {@code gg_geo_distance(f, lng, lat)}</td>
 * <td>{@link SqliteGeo}</td></tr>
 * </table>
 */
final class SqliteFunctions {

    static final String ORDER_KEY = "gg_okey";
    static final String FLOAT_ORDER_KEY = "gg_okey_float";
    static final String ORDER_PART = "gg_opart";
    static final String REAL_TEXT = "gg_real_text";
    static final String GEO_WITHIN = "gg_geo_within";
    static final String GEO_INTERSECTS = "gg_geo_intersects";
    static final String GEO_DISTANCE = "gg_geo_distance";

    private static final String NAN = "NaN";
    private static final int DETERMINISTIC = Function.FLAG_DETERMINISTIC;

    private static final Map<SQLiteConnection, Boolean> READIED = Collections.synchronizedMap(new WeakHashMap<>());

    private SqliteFunctions() {
    }

    /**
     * Readies a connection, once per physical connection.
     *
     * @param connection the connection, possibly a pool's proxy
     * @throws SQLException when it is no SQLite connection, or a function cannot be registered
     */
    static void ready(Connection connection) throws SQLException {
        SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
        if (READIED.containsKey(sqlite)) {
            return;
        }
        synchronized (sqlite) {
            if (READIED.containsKey(sqlite)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            // sqlite-jdbc registers a function only on its own connection class, never on a pool's proxy.
            register(sqlite);
            READIED.put(sqlite, Boolean.TRUE);
        }
    }

    private static void register(Connection c) throws SQLException {
        Function.create(c, ORDER_KEY, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                result(orderKey(false));
            }
        }, 1, DETERMINISTIC);
        Function.create(c, FLOAT_ORDER_KEY, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                result(orderKey(true));
            }
        }, 1, DETERMINISTIC);
        Function.create(c, ORDER_PART, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                result(switch (value_type(0)) {
                    case SQLITE_NULL -> SqliteOrderKey.ABSENT;
                    case SQLITE_INTEGER -> SqliteOrderKey.integer(value_long(0));
                    default -> value_text(0);
                });
            }
        }, 1, DETERMINISTIC);
        registerReals(c);
        registerSearch(c);
        registerGeo(c);
    }

    private static void registerReals(Connection c) throws SQLException {
        Function.create(c, REAL_TEXT, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                switch (value_type(0)) {
                    case SQLITE_NULL -> result();
                    case SQLITE_INTEGER, SQLITE_FLOAT -> result(Double.toString(value_double(0)));
                    default -> result(value_text(0));
                }
            }
        }, 1, DETERMINISTIC);
    }

    private static void registerSearch(Connection c) throws SQLException {
        Function.create(c, SqliteRegex.FUNCTION, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                if (value_type(0) == SQLITE_NULL) {
                    result();
                } else {
                    result(SqliteRegex.find(value_text(0), value_text(1)) ? 1 : 0);
                }
            }
        }, 2, DETERMINISTIC);
        Function.create(c, SqliteTextSearch.FUNCTION, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                result(SqliteTextSearch.matches(value_text(0), value_text(1)) ? 1 : 0);
            }
        }, 2, DETERMINISTIC);
    }

    private static void registerGeo(Connection c) throws SQLException {
        Function.create(c, GEO_WITHIN, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                if (value_type(0) == SQLITE_NULL || value_type(1) == SQLITE_NULL) {
                    result();
                } else {
                    result(SqliteGeo.within(value_text(0), value_text(1)) ? 1 : 0);
                }
            }
        }, 2, DETERMINISTIC);
        Function.create(c, GEO_INTERSECTS, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                if (value_type(0) == SQLITE_NULL || value_type(1) == SQLITE_NULL) {
                    result();
                } else {
                    result(SqliteGeo.intersects(value_text(0), value_text(1)) ? 1 : 0);
                }
            }
        }, 2, DETERMINISTIC);
        Function.create(c, GEO_DISTANCE, new Fn() {
            @Override
            protected void xFunc() throws SQLException {
                if (value_type(0) == SQLITE_NULL) {
                    result();
                } else {
                    result(SqliteGeo.distance(value_text(0), value_double(1), value_double(2)));
                }
            }
        }, 3, DETERMINISTIC);
    }

    /** A function of this DAO, with the value-type codes of SQLite's C API. */
    private abstract static class Fn extends Function {

        static final int SQLITE_INTEGER = 1;
        static final int SQLITE_FLOAT = 2;
        static final int SQLITE_BLOB = 4;
        static final int SQLITE_NULL = 5;

        /** The order key of the first argument, or NULL for NULL. */
        String orderKey(boolean floating) throws SQLException {
            return switch (value_type(0)) {
                case SQLITE_NULL -> null;
                case SQLITE_INTEGER -> SqliteOrderKey.number(value_long(0));
                case SQLITE_FLOAT -> SqliteOrderKey.number(value_double(0));
                case SQLITE_BLOB -> SqliteOrderKey.blob(value_blob(0));
                default -> floating && NAN.equals(value_text(0)) ? SqliteOrderKey.number(Double.NaN)
                        : SqliteOrderKey.text(value_text(0));
            };
        }
    }
}
