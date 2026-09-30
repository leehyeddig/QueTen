package com.queten.collect;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.queten.client.qoo10.Qoo10Client;
import com.queten.config.AppConfig;
import com.queten.dao.CollectStatusDao;
import com.queten.dao.Qoo10OrderDao;
import com.queten.model.Qoo10Order;

/**
 * Qoo10 주문 주기 수집.
 * 마지막 성공 시각에서 qoo10.overlap.hours 만큼 앞부터 지금까지 다시 받아 주문번호 기준으로 덮어쓴다
 * (경계 누락 방지 + 그 사이 바뀐 주문상태 반영).
 * 성공 이력이 없으면(최초 실행) qoo10.backfill.days 만큼 과거부터 받는다 (API가 90일 단위로 나눠 호출).
 */
public class Qoo10Collector implements Runnable {

    static final String SOURCE = "QOO10";

    private static final Logger LOG = Logger.getLogger(Qoo10Collector.class.getName());

    private final AppConfig config;
    private final Qoo10OrderDao dao = new Qoo10OrderDao();
    private final CollectStatusDao statusDao = new CollectStatusDao();

    // 아래 값은 설정이 바뀌면(config.version 변경) 다시 만든다.
    private int configVersion = -1;
    private Qoo10Client client;
    private ZoneId zone;
    private int overlapHours;
    private int backfillDays;
    private boolean notReadyLogged;

    public Qoo10Collector(AppConfig config) {
        this.config = config;
    }

    private void applyConfigIfChanged() {
        if (configVersion == config.version()) {
            return;
        }
        client = new Qoo10Client(config);
        zone = config.zone();
        overlapHours = Math.max(0, config.getInt("qoo10.overlap.hours", 24));
        backfillDays = Math.max(1, config.getInt("qoo10.backfill.days", 365));
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

            Optional<LocalDateTime> lastSuccess = statusDao.lastSuccess(SOURCE);
            LocalDateTime from = lastSuccess
                    .map(t -> t.minusHours(overlapHours))
                    .orElse(startedAt.minusDays(backfillDays));
            if (lastSuccess.isEmpty()) {
                LOG.info("Qoo10 최초 백필 시작: " + from + " ~ " + startedAt);
            }

            List<Qoo10Order> orders = client.fetchOrders(from, startedAt);
            int saved = dao.upsert(orders, LocalDateTime.now(zone));

            // 수집 "시작" 시각을 성공 시각으로 남겨, 수집 도중 들어온 주문도 다음 실행 범위에 포함시킨다.
            statusDao.markSuccess(SOURCE, startedAt);
            LOG.info("Qoo10 수집 완료: " + from + " ~ " + startedAt + ", " + saved + "건");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Qoo10 수집 실패 - 기존 데이터는 그대로 유지", e);
            CollectorSupport.recordFailure(statusDao, SOURCE, startedAt, CollectorSupport.describe(e));
        }
    }

    private void skip(String reason, LocalDateTime at) {
        if (!notReadyLogged) {
            LOG.warning("Qoo10 수집 건너뜀: " + reason);
            notReadyLogged = true;
        }
        CollectorSupport.recordFailure(statusDao, SOURCE, at, reason);
    }
}
