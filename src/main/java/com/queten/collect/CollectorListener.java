package com.queten.collect;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.queten.config.AppConfig;
import com.queten.db.ConnectionUtil;
import com.queten.db.SchemaInitializer;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/** 톰캣 시작 시 설정·DB를 준비하고 수집 스케줄러를 띄운다. 종료 시 스케줄러와 DB를 정리한다. */
@WebListener
public class CollectorListener implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(CollectorListener.class.getName());
    private static final long SHUTDOWN_WAIT_SECONDS = 10;
    private static final long CONFIG_CHECK_SECONDS = 30;

    private ScheduledExecutorService scheduler;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();
        String realPath = ctx.getRealPath(AppConfig.CONFIG_PATH);
        AppConfig config = AppConfig.load(() -> ctx.getResourceAsStream(AppConfig.CONFIG_PATH),
                realPath == null ? null : Path.of(realPath), AppConfig.CONFIG_PATH);
        ConnectionUtil.init(config);
        SchemaInitializer.run(config);

        AtomicInteger seq = new AtomicInteger();
        // Meta·Qoo10 수집 + 설정 변경 확인이 서로 기다리지 않도록 3개
        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "queten-collector-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        long metaMinutes = config.getInt("meta.interval.minutes", 10);
        long qoo10Minutes = config.getInt("qoo10.interval.minutes", 15);
        MetaCollector meta = new MetaCollector(config);
        Qoo10Collector qoo10 = new Qoo10Collector(config);

        // fixed delay: 이전 실행(백필 등)이 끝난 뒤부터 주기를 세므로 같은 수집이 겹쳐 돌지 않는다.
        scheduler.scheduleWithFixedDelay(meta, 5, metaMinutes * 60, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(qoo10, 10, qoo10Minutes * 60, TimeUnit.SECONDS);

        // config.properties가 바뀌면 다시 읽고 바로 한 번 수집한다 (토큰 교체 등을 재시작 없이 반영).
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (config.reloadIfChanged()) {
                    LOG.info("config.properties 변경 감지 → 설정을 다시 읽고 즉시 수집합니다.");
                    scheduler.execute(meta);
                    scheduler.execute(qoo10);
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "config.properties 다시 읽기 실패 - 기존 설정 유지", e);
            }
        }, CONFIG_CHECK_SECONDS, CONFIG_CHECK_SECONDS, TimeUnit.SECONDS);

        LOG.info("수집 스케줄러 시작: Meta " + metaMinutes + "분, Qoo10 " + qoo10Minutes + "분 간격, 설정 변경 확인 "
                + CONFIG_CHECK_SECONDS + "초 간격");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    // 진행 중인 수집에 인터럽트를 보내 HTTP 대기 등을 끊는다.
                    scheduler.shutdownNow();
                    scheduler.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOG.info("수집 스케줄러 종료");
        }
        ConnectionUtil.shutdown();
    }
}
