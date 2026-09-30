package com.queten.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.queten.config.AppConfig;

/** 기동 시 schema 파일(CREATE TABLE IF NOT EXISTS ...)을 실행한다. */
public final class SchemaInitializer {

    private static final Logger LOG = Logger.getLogger(SchemaInitializer.class.getName());

    private SchemaInitializer() {
    }

    public static void run(AppConfig config) {
        String file = config.get("db.schema", "schema-h2.sql");
        String script = readResource(file);
        // "--" 로 시작하는 줄은 주석으로 보고 제거한 뒤 ';' 단위로 실행
        String withoutComments = script.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));

        try (Connection c = ConnectionUtil.getConnection(); Statement st = c.createStatement()) {
            for (String sql : withoutComments.split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql.trim());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("스키마 초기화 실패 (" + file + ")", e);
        }
        LOG.info("스키마 확인 완료: " + file);
    }

    private static String readResource(String file) {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream(file)) {
            if (in == null) {
                throw new IllegalStateException("스키마 파일이 없습니다: com/queten/db/" + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("스키마 파일 읽기 실패: " + file, e);
        }
    }
}
