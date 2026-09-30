package com.queten.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * 설정값 조회. 같은 키의 환경변수가 있으면 환경변수가 우선한다.
 * 환경변수 이름은 키를 대문자로 바꾸고 '.'을 '_'로 바꾼 것 (meta.access.token → META_ACCESS_TOKEN).
 */
public final class AppConfig {

    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());
    public static final String CONFIG_PATH = "/WEB-INF/config.properties";

    /** 저장 중인 파일을 반쯤 읽지 않도록, 마지막 수정 후 이 시간이 지나야 다시 읽는다. */
    private static final long STABLE_MILLIS = 2000;

    private static volatile AppConfig instance;

    /** 설정 파일을 여는 방법. 파일이 없으면 null을 돌려준다. */
    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    private final Opener opener;
    /** 실제 파일 경로. 없으면(war 안의 리소스 등) null이고 자동 반영이 안 된다. */
    private final Path file;
    private final String label;
    private volatile Properties props;
    /** 설정을 다시 읽을 때마다 1씩 증가. 설정값을 캐시하는 쪽이 바뀌었는지 비교할 때 쓴다. */
    private volatile int version;
    private long loadedModified;

    private AppConfig(Opener opener, Path file, String label) {
        this.opener = opener;
        this.file = file;
        this.label = label;
        this.props = read();
        this.loadedModified = lastModified();
    }

    /** 서블릿 API에 의존하지 않도록, 여는 방법과 실제 파일 경로(없으면 null)를 받는다. */
    public static AppConfig load(Opener opener, Path file, String label) {
        instance = new AppConfig(opener, file, label);
        return instance;
    }

    /** 톰캣 없이 실행할 때(정적 내보내기) 파일에서 바로 읽는다. */
    public static AppConfig loadFile(Path file) {
        return load(() -> Files.newInputStream(file), file, file.toString());
    }

    /**
     * config.properties가 바뀌었으면 다시 읽는다. 바뀌었으면 true.
     * 토큰·환율·상품코드 등은 재시작 없이 반영되지만, db.* 와 *.interval.minutes 는 재시작해야 반영된다.
     */
    public synchronized boolean reloadIfChanged() {
        long modified = lastModified();
        if (modified <= 0 || modified == loadedModified
                || System.currentTimeMillis() - modified < STABLE_MILLIS) {
            return false;
        }
        props = read();
        loadedModified = modified;
        version++;
        return true;
    }

    public int version() {
        return version;
    }

    private Properties read() {
        Properties p = new Properties();
        try (InputStream in = opener.open()) {
            if (in == null) {
                LOG.warning(label + " 파일이 없어 환경변수만 사용합니다.");
            } else {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(label + " 읽기 실패", e);
        }
        return p;
    }

    /** 파일 수정 시각. war로 배포돼 실제 파일 경로가 없으면 0 (자동 반영 안 됨). */
    private long lastModified() {
        if (file == null) {
            return 0;
        }
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public static AppConfig get() {
        AppConfig c = instance;
        if (c == null) {
            throw new IllegalStateException("AppConfig가 아직 초기화되지 않았습니다.");
        }
        return c;
    }

    /** 값이 없거나 비어 있으면 null. */
    public String get(String key) {
        String env = System.getenv(envName(key));
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    public String get(String key, String defaultValue) {
        String v = get(key);
        return v == null ? defaultValue : v;
    }

    public String require(String key) {
        String v = get(key);
        if (v == null) {
            throw new IllegalStateException("설정 누락: " + key + " (환경변수 " + envName(key) + ")");
        }
        return v;
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("설정값이 숫자가 아닙니다: " + key + "=" + v);
        }
    }

    /** 값이 없으면 null. */
    public BigDecimal getDecimal(String key) {
        String v = get(key);
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("설정값이 숫자가 아닙니다: " + key + "=" + v);
        }
    }

    /** 쉼표로 구분된 목록. 값이 없으면 빈 목록. */
    public List<String> getList(String key) {
        String v = get(key);
        if (v == null) {
            return List.of();
        }
        return Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 날짜 경계 기준 타임존 (Qoo10 Japan = JST). */
    public ZoneId zone() {
        return ZoneId.of(get("app.zone", "Asia/Tokyo"));
    }

    public static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_');
    }
}
