package com.queten.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.queten.db.ConnectionUtil;
import com.queten.model.MetaCampaignDaily;

public class MetaInsightDao {

    private static final String UPSERT = """
            INSERT INTO meta_campaign_daily
                (stat_date, campaign_id, campaign_name, currency, spend, impressions, reach, clicks, link_clicks, collected_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                campaign_name = VALUES(campaign_name),
                currency      = VALUES(currency),
                spend         = VALUES(spend),
                impressions   = VALUES(impressions),
                reach         = VALUES(reach),
                clicks        = VALUES(clicks),
                link_clicks   = VALUES(link_clicks),
                collected_at  = VALUES(collected_at)
            """;

    /** (날짜, 캠페인ID) 기준 UPSERT. 한 트랜잭션으로 처리해 실패하면 기존 데이터가 그대로 남는다. */
    public int upsert(List<MetaCampaignDaily> rows, LocalDateTime collectedAt) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        try (Connection c = ConnectionUtil.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (MetaCampaignDaily r : rows) {
                    ps.setObject(1, r.statDate());
                    ps.setString(2, r.campaignId());
                    ps.setString(3, r.campaignName());
                    ps.setString(4, r.currency());
                    ps.setBigDecimal(5, r.spend());
                    ps.setLong(6, r.impressions());
                    ps.setLong(7, r.reach());
                    ps.setLong(8, r.clicks());
                    ps.setLong(9, r.linkClicks());
                    ps.setObject(10, collectedAt);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
        return rows.size();
    }
}
