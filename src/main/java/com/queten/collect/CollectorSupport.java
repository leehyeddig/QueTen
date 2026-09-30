package com.queten.collect;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.queten.dao.CollectStatusDao;

final class CollectorSupport {

    private static final Logger LOG = Logger.getLogger(CollectorSupport.class.getName());

    private CollectorSupport() {
    }

    /** 대시보드에 보여줄 실패 사유. 예외 클래스명 대신 메시지를 쓴다. */
    static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /** 실패 기록 자체가 실패해도(DB 문제 등) 스케줄러가 멈추지 않도록 로그만 남긴다. */
    static void recordFailure(CollectStatusDao statusDao, String source, LocalDateTime at, String error) {
        try {
            statusDao.markFailure(source, at, error);
        } catch (SQLException e) {
            LOG.log(Level.WARNING, source + " 실패 기록 저장 실패", e);
        }
    }
}
