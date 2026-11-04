package com.garganttua.dao.postgresql;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * A data source that COUNTS the round trips its connections make to the server — what an operation
 * really costs over a network, whatever the SQL.
 *
 * <p>
 * Counted: every statement executed (a batch counts once — pgjdbc sends it in one go), every
 * {@code commit} and {@code rollback}, and every {@code setTransactionIsolation}, which pgjdbc sends to
 * the server at once as a {@code SET SESSION CHARACTERISTICS}. Not counted, because pgjdbc sends
 * nothing for them: {@code setAutoCommit} (the {@code BEGIN} rides with the next statement) and
 * {@code setReadOnly} (read-only mode {@code transaction}, the default). The statements themselves are
 * kept, so a test can say WHICH ones were sent.
 * </p>
 *
 * <p>
 * It wraps ONE connection, lent again and again, as a pool of one would: the counts are those of a
 * pooled deployment, where opening a connection is not part of an operation.
 * </p>
 */
public final class RoundTripCounter implements DataSource {

    private static final Set<String> EXECUTIONS = Set.of("execute", "executeQuery", "executeUpdate",
            "executeBatch", "executeLargeUpdate", "executeLargeBatch");
    private static final Set<String> SERVER_CALLS = Set.of("commit", "rollback", "setTransactionIsolation");

    private final Connection target;
    private final AtomicInteger trips = new AtomicInteger();
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private final Connection lent;

    /**
     * @param source where the one connection comes from
     * @throws SQLException when it cannot be opened
     */
    public RoundTripCounter(DataSource source) throws SQLException {
        this.target = source.getConnection();
        this.lent = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
                this::onConnection);
    }

    /** {@return the round trips since the last reset} */
    public int trips() {
        return this.trips.get();
    }

    /** {@return what was sent since the last reset: SQL, or the name of a server call} */
    public List<String> sent() {
        synchronized (this.sent) {
            return List.copyOf(this.sent);
        }
    }

    /** Starts counting again. */
    public void reset() {
        this.trips.set(0);
        this.sent.clear();
    }

    /** Closes the real connection. */
    public void close() throws SQLException {
        this.target.close();
    }

    private Object onConnection(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("close".equals(name)) {
            return null;
        }
        if (SERVER_CALLS.contains(name)) {
            this.trips.incrementAndGet();
            this.sent.add(name + (args == null ? "" : java.util.Arrays.toString(args)));
        }
        Object result = invoke(this.target, method, args);
        if (result instanceof PreparedStatement statement) {
            String sql = args != null && args.length > 0 ? String.valueOf(args[0]) : "?";
            return wrap(statement, PreparedStatement.class, sql);
        }
        if (result instanceof Statement statement) {
            return wrap(statement, Statement.class, null);
        }
        return result;
    }

    private <T extends Statement> T wrap(T statement, Class<T> type, String sql) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (EXECUTIONS.contains(method.getName())) {
                this.trips.incrementAndGet();
                this.sent.add(sql != null ? sql : args != null && args.length > 0 ? String.valueOf(args[0]) : "batch");
            }
            return invoke(statement, method, args);
        };
        return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Override
    public Connection getConnection() {
        return this.lent;
    }

    @Override
    public Connection getConnection(String user, String password) {
        return this.lent;
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // unused
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // unused
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
