package com.queten.collect;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.queten.client.meta.MetaInsightsClient;
import com.queten.config.AppConfig;
import com.queten.dao.CollectStatusDao;
import com.queten.dao.MetaInsightDao;

/**
 * Meta Insights 주기 수집.
 * 평소에는 최근 N일(meta.recollect.days)을 다시 받아 덮어쓴다 (Meta가 과거 수치를 보정하기 때문).
 * 성공 이력이 없으면(최초 실행) meta.backfill.days 만큼 과거를 meta.chunk.days 단위로 나눠 받는다.
 */
public class MetaCollector implements Runnable {

    static final String SOURCE = "META";

    private static final Logger LOG = Logger.getLogger(MetaCollector.class.getName());

    private final AppConfig config;
    private final MetaInsightDao dao = new MetaInsightDao();
    private final CollectStatusDao statusDao = new CollectStatusDao();

    // 아래 값은 설정이 바뀌면(config.version 변경) 다시 만든다.
    private int configVersion = -1;
    private MetaInsightsClient client;
    private ZoneId zone;
    private int recollectDays;
    private int backfillDays;
    private int chunkDays;
    private boolean notReadyLogged;

    public MetaCollector(AppConfig config) {
        this.config = config;
    }

    private void applyConfigIfChanged() {
        if (configVersion == config.version()) {
            return;
        }
        client = new MetaInsightsClient(config);
        zone = config.zone();
        recollectDays = Math.max(1, config.getInt("meta.recollect.days", 3));
        backfillDays = Math.max(1, config.getInt("meta.backfill.days", 1095));
        chunkDays = Math.max(1, config.getInt("meta.chunk.days", 30));
        notReadyLogged = false;
        configVersion = config.version();
    }

    // 예외를 밖으로 던지면 ScheduledExecutorService가 이후 실행을 멈추므로 전부 여기서 잡는다.
    // 주기 실행과 설정 변경 직후 실행이 겹치지 않도록 synchronized.
    @Override
    public synchronized void run() {
        applyConfigIfChanged();
        LocalDateTime startedAt = LocalDateTime.now(zone);
        try {
            String notReady = client.notReadyReason();
            if (notReady != null) {
                skip(notReady, startedAt);
                return;
            }

            LocalDate today = startedAt.toLocalDate();
            boolean firstRun = statusDao.lastSuccess(SOURCE).isEmpty();
            LocalDate from = today.minusDays((firstRun ? backfillDays : recollectDays) - 1L);
            if (firstRun) {
                LOG.info("Meta 최초 백필 시작: " + from + " ~ " + today);
            }

            int saved = 0;
            for (LocalDate chunkFrom = from; !chunkFrom.isAfter(today); chunkFrom = chunkFrom.plusDays(chunkDays)) {
                if (Thread.currentThread().isInterrupted()) {
                    LOG.info("Meta 수집 중단 (종료 요청)");
                    return;
                }
                LocalDate chunkTo = chunkFrom.plusDays(chunkDays - 1L);
                if (chunkTo.isAfter(today)) {
                    chunkTo = today;
                }
                saved += dao.upsert(client.fetchDaily(chunkFrom, chunkTo), LocalDateTime.now(zone));
            }

            statusDao.markSuccess(SOURCE, startedAt);
            LOG.info("Meta 수집 완료: " + from + " ~ " + today + ", " + saved + "행");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Meta 수집 실패 - 기존 데이터는 그대로 유지", e);
            CollectorSupport.recordFailure(statusDao, SOURCE, startedAt, CollectorSupport.describe(e));
        }
    }

    private void skip(String reason, LocalDateTime at) {
        if (!notReadyLogged) {
            LOG.warning("Meta 수집 건너뜀: " + reason);
            notReadyLogged = true;
        }
        CollectorSupport.recordFailure(statusDao, SOURCE, at, reason);
    }
}
