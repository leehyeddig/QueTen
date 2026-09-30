package com.queten.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.queten.db.ConnectionUtil;

/** 소스(META / QOO10)별 마지막 수집 성공 시각. */
public class CollectStatusDao {

    private static final int MAX_ERROR_LENGTH = 1000;

    public Optional<LocalDateTime> lastSuccess(String source) throws SQLException {
        try (Connection c = ConnectionUtil.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT last_success_at FROM collect_status WHERE source = ?")) {
            ps.setString(1, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.ofNullable(rs.getObject(1, LocalDateTime.class))
                        : Optional.empty();
            }
        }
    }

    public Map<String, LocalDateTime> lastSuccessAll() throws SQLException {
        Map<String, LocalDateTime> result = new HashMap<>();
        try (Connection c = ConnectionUtil.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT source, last_success_at FROM collect_status");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getObject(2, LocalDateTime.class));
            }
        }
        return result;
    }

    /** 마지막 시도가 실패한 소스의 오류 메시지. 성공하면 NULL로 지워지므로 여기에 없는 소스는 정상이다. */
    public Map<String, String> lastErrorAll() throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (Connection c = ConnectionUtil.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT source, last_error FROM collect_status WHERE last_error IS NOT NULL");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        }
        return result;
    }

    public void markSuccess(String source, LocalDateTime at) throws SQLException {
        execute("""
                INSERT INTO collect_status (source, last_success_at, last_attempt_at, last_error)
                VALUES (?, ?, ?, NULL)
                ON DUPLICATE KEY UPDATE
                    last_success_at = VALUES(last_success_at),
                    last_attempt_at = VALUES(last_attempt_at),
                    last_error      = NULL
                """, source, at, at);
    }

    /** 실패는 시도 시각과 오류만 남기고, 마지막 성공 시각은 건드리지 않는다. */
    public void markFailure(String source, LocalDateTime at, String error) throws SQLException {
        String trimmed = (error != null && error.length() > MAX_ERROR_LENGTH)
                ? error.substring(0, MAX_ERROR_LENGTH)
                : error;
        execute("""
                INSERT INTO collect_status (source, last_attempt_at, last_error)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    last_attempt_at = VALUES(last_attempt_at),
                    last_error      = VALUES(last_error)
                """, source, at, trimmed);
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = ConnectionUtil.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }
}
