package com.kaarigarsetu.config;

import com.kaarigarsetu.exception.DataAccessException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * HikariCP pool plus a thread-bound transaction. Inside {@link #inTransaction} every DAO call on this thread
 * receives the same connection (wrapped so the DAOs' try-with-resources close() does not release it), and the
 * whole unit commits or rolls back together. Outside a transaction it behaves like a normal pooled provider.
 */
public final class PooledConnectionProvider implements ConnectionProvider, Transactions, AutoCloseable {
    private final HikariDataSource dataSource;
    private final ThreadLocal<Connection> current = new ThreadLocal<>();

    public PooledConnectionProvider(DatabaseConfig config, int maxPoolSize) {
        HikariConfig hikari = new HikariConfig();
        hikari.setDriverClassName("org.postgresql.Driver");   // webapp classloader: DriverManager discovery fails
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(maxPoolSize);
        hikari.setConnectionTimeout(5_000);
        hikari.setPoolName("kaarigar-setu");
        hikari.setConnectionInitSql("SET TIME ZONE 'UTC'");   // all TIMESTAMP columns are UTC, matching Clock.systemUTC()
        this.dataSource = new HikariDataSource(hikari);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection bound = current.get();
        return bound != null ? nonClosing(bound) : dataSource.getConnection();
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (current.get() != null) {
            return work.get();   // join the surrounding transaction
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            current.set(connection);
            try {
                T result = work.get();
                connection.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            } finally {
                current.remove();
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new DataAccessException("Failed to run transaction", exception);
        }
    }

    private static Connection nonClosing(Connection real) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("close")) {
                return null;
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
