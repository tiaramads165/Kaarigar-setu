package com.kaarigarsetu.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConfig {
    private final String host;
    private final String port;
    private final String database;
    private final String user;
    private final String password;

    public DatabaseConfig() {
        this(
                readRequiredEnv("DB_HOST"),
                readRequiredEnv("DB_PORT"),
                readRequiredEnv("DB_NAME"),
                readRequiredEnv("DB_USER"),
                readRequiredEnv("DB_PASSWORD")
        );
    }

    public DatabaseConfig(String host, String port, String database, String user, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), user, password);
    }

    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    private static String readRequiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " environment variable is required");
        }
        return value;
    }
}
