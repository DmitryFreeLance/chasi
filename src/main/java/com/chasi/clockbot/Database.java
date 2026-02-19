package com.chasi.clockbot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private final String jdbcUrl;

    public Database(String dbPath) {
        ensureParentDirectory(dbPath);
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        init();
    }

    private void ensureParentDirectory(String dbPath) {
        try {
            Path path = Path.of(dbPath).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            System.err.println("Failed to create DB directory: " + e.getMessage());
        }
    }

    private void init() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS requests ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "user_id INTEGER,"
                + "username TEXT,"
                + "file_id TEXT,"
                + "image_url TEXT,"
                + "result_time TEXT,"
                + "status TEXT,"
                + "error TEXT,"
                + "created_at TEXT DEFAULT (datetime('now'))"
                + ")");
        } catch (SQLException e) {
            System.err.println("Failed to initialize database: " + e.getMessage());
        }
    }

    public void logRequest(RequestLog log) {
        if (log == null) {
            return;
        }
        String sql = "INSERT INTO requests (user_id, username, file_id, image_url, result_time, status, error) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (log.userId() == null) {
                statement.setNull(1, java.sql.Types.INTEGER);
            } else {
                statement.setLong(1, log.userId());
            }
            statement.setString(2, log.username());
            statement.setString(3, log.fileId());
            statement.setString(4, log.imageUrl());
            statement.setString(5, log.resultTime());
            statement.setString(6, log.status());
            statement.setString(7, log.errorMessage());
            statement.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to log request: " + e.getMessage());
        }
    }
}
