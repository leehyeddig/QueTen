package com.queten.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.queten.collect.MetaCollector;
import com.queten.collect.Qoo10Collector;
import com.queten.config.AppConfig;
import com.queten.dashboard.DashboardResponse;
import com.queten.dashboard.DashboardService;
import com.queten.db.ConnectionUtil;
import com.queten.db.SchemaInitializer;

/**
 * 톰캣 없이 한 번 수집하고, 대시보드 응답을 정적 JSON 파일로 내보낸다 (GitHub Actions + Pages 용).
 * 실행: java com.queten.cli.StaticExport {설정파일} {출력폴더}
 *
 * <p>/api/dashboard 와 달리 조회 조건을 받을 수 없으므로, 화면에서 고를 수 있는 조합(기간 x 상품)을 전부 미리 만든다.
 * 파일 이름: dashboard-{일수}-{상품코드 | all}.json
 */
public final class StaticExport {

    private static final Logger LOG = Logger.getLogger(StaticExport.class.getName());
    /** dashboard.html 의 기간 버튼과 같아야 한다. */
    private static final List<Integer> PERIOD_DAYS = List.of(7, 14, 30);
    private static final String ALL_PRODUCTS = "all";
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private StaticExport() {
    }

    public static void main(String[] args) throws IOException, SQLException {
        if (args.length != 2) {
            System.err.println("사용법: StaticExport {설정파일} {출력폴더}");
            System.exit(2);
        }
        Path outDir = Path.of(args[1]);

        AppConfig config = AppConfig.loadFile(Path.of(args[0]));
        ConnectionUtil.init(config);
        try {
            SchemaInitializer.run(config);

            // 수집 실패는 각 수집기가 collect_status에 남기고, 내보낸 JSON의 collectErrors로 화면에 보인다.
            new MetaCollector(config).run();
            new Qoo10Collector(config).run();

            export(config, outDir);
        } finally {
            ConnectionUtil.shutdown();
        }
    }

    private static void export(AppConfig config, Path outDir) throws IOException, SQLException {
        Files.createDirectories(outDir);
        DashboardService service = new DashboardService(config);
        LocalDate to = LocalDate.now(config.zone());

        // 상품 선택 목록에 나올 수 있는 코드 = 설정의 광고 상품 + 가장 긴 기간에 주문이 있는 상품
        int longest = PERIOD_DAYS.stream().mapToInt(Integer::intValue).max().orElseThrow();
        DashboardResponse widest = service.build(to.minusDays(longest - 1L), to, null);
        Set<String> productCodes = new LinkedHashSet<>(widest.productCodes());
        widest.products().forEach(p -> productCodes.add(p.productCode()));

        int files = 0;
        for (int days : PERIOD_DAYS) {
            LocalDate from = to.minusDays(days - 1L);
            write(outDir, days, ALL_PRODUCTS, service.build(from, to, null));
            files++;
            for (String code : productCodes) {
                // 코드가 파일 이름이 되므로 안전한 문자만 허용한다.
                if (!code.matches("[A-Za-z0-9_-]+") || ALL_PRODUCTS.equals(code)) {
                    LOG.warning("파일 이름으로 쓸 수 없는 상품코드라 건너뜀: " + code);
                    continue;
                }
                write(outDir, days, code, service.build(from, to, code));
                files++;
            }
        }
        LOG.info("정적 JSON 내보내기 완료: " + files + "개 → " + outDir.toAbsolutePath());
    }

    private static void write(Path outDir, int days, String product, DashboardResponse response) throws IOException {
        Path file = outDir.resolve("dashboard-" + days + "-" + product + ".json");
        Files.writeString(file, GSON.toJson(response), StandardCharsets.UTF_8);
    }
}
