package com.queten.db;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.queten.config.AppConfig;

/**
 * JDBC 커넥션 유틸. 요청마다 새 커넥션을 열고, 쓰는 쪽에서 try-with-resources로 닫는다.
 * SQL은 H2(MODE=MySQL)와 MySQL 양쪽에서 도는 문법만 쓰므로 DB를 바꿀 때는 설정만 바꾸면 된다.
 */
public final class ConnectionUtil {

    private static final Logger LOG = Logger.getLogger(ConnectionUtil.class.getName());

    private static volatile String url;
    private static volatile String user;
    private static volatile String password;

    private ConnectionUtil() {
    }

    public static void init(AppConfig config) {
        String driver = config.get("db.driver", "org.h2.Driver");
        try {
            // Tomcat은 WEB-INF/lib의 JDBC 드라이버를 자동 등록하지 않으므로 직접 로드한다.
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDBC 드라이버를 찾을 수 없습니다: " + driver
                    + " (WEB-INF/lib에 jar가 있는지 확인)", e);
        }
        url = config.require("db.url");
        user = config.get("db.user", "");
        password = config.get("db.password", "");
    }

    public static Connection getConnection() throws SQLException {
        if (url == null) {
            throw new IllegalStateException("ConnectionUtil.init()이 호출되지 않았습니다.");
        }
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * 웹앱 종료 시 호출. H2 파일 잠금을 풀고, 이 웹앱이 등록한 드라이버를 해제한다.
     * (재배포 때 "Database may be already in use"와 클래스로더 누수 경고를 막기 위함)
     */
    public static void shutdown() {
        if (url != null && url.startsWith("jdbc:h2:")) {
            try (Connection c = getConnection(); Statement st = c.createStatement()) {
                st.execute("SHUTDOWN");
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "H2 SHUTDOWN 실패", e);
            }
        }
        url = null;

        ClassLoader webappLoader = ConnectionUtil.class.getClassLoader();
        for (Driver d : Collections.list(DriverManager.getDrivers())) {
            if (d.getClass().getClassLoader() == webappLoader) {
                try {
                    DriverManager.deregisterDriver(d);
                } catch (SQLException e) {
                    LOG.log(Level.WARNING, "JDBC 드라이버 해제 실패: " + d, e);
                }
            }
        }
    }
}
