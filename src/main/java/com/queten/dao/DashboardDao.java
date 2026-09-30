package com.queten.dao;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.queten.db.ConnectionUtil;

/** 대시보드용 집계 조회. 비율 계산은 하지 않고 합계만 돌려준다. */
public class DashboardDao {

    /**
     * @param campaignIds      비어 있으면 모든 캠페인
     * @param itemCodes        비어 있으면 모든 상품
     * @param excludedStatuses 매출 집계에서 뺄 주문상태
     */
    public record Filter(LocalDate from, LocalDate to, List<String> campaignIds,
            List<String> itemCodes, List<String> excludedStatuses) {
    }

    public record MetaDay(LocalDate date, BigDecimal spend, long impressions, long linkClicks) {
    }

    public record CampaignTotal(String campaignId, String campaignName, BigDecimal spend,
            long impressions, long linkClicks) {
    }

    public record OrderDay(LocalDate date, long orders, long quantity, BigDecimal revenue) {
    }

    public record ProductTotal(String itemCode, long orders, long quantity, BigDecimal revenue) {
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    public List<String> spendCurrencies(Filter f) throws SQLException {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT DISTINCT currency FROM meta_campaign_daily" + metaWhere(f, args)
                + " AND currency IS NOT NULL";
        return query(sql, args, rs -> rs.getString(1));
    }

    public List<MetaDay> metaDaily(Filter f) throws SQLException {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT stat_date, SUM(spend), SUM(impressions), SUM(link_clicks)"
                + " FROM meta_campaign_daily" + metaWhere(f, args)
                + " GROUP BY stat_date";
        return query(sql, args, rs -> new MetaDay(
                rs.getObject(1, LocalDate.class), rs.getBigDecimal(2), rs.getLong(3), rs.getLong(4)));
    }

    public List<CampaignTotal> campaigns(Filter f) throws SQLException {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT campaign_id, MAX(campaign_name), SUM(spend), SUM(impressions), SUM(link_clicks)"
                + " FROM meta_campaign_daily" + metaWhere(f, args)
                + " GROUP BY campaign_id ORDER BY SUM(spend) DESC";
        return query(sql, args, rs -> new CampaignTotal(
                rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getLong(4), rs.getLong(5)));
    }

    public List<OrderDay> orderDaily(Filter f) throws SQLException {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT order_ymd, COUNT(*), SUM(quantity), SUM(amount)"
                + " FROM qoo10_order" + orderWhere(f, args)
                + " GROUP BY order_ymd";
        return query(sql, args, rs -> new OrderDay(
                rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)));
    }

    public List<ProductTotal> products(Filter f) throws SQLException {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT item_code, COUNT(*), SUM(quantity), SUM(amount)"
                + " FROM qoo10_order" + orderWhere(f, args)
                + " GROUP BY item_code ORDER BY SUM(amount) DESC";
        return query(sql, args, rs -> new ProductTotal(
                rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)));
    }

    private static String metaWhere(Filter f, List<Object> args) {
        args.add(f.from());
        args.add(f.to());
        return " WHERE stat_date BETWEEN ? AND ?" + in("campaign_id", f.campaignIds(), args);
    }

    private static String orderWhere(Filter f, List<Object> args) {
        args.add(f.from());
        args.add(f.to());
        String sql = " WHERE order_ymd BETWEEN ? AND ?" + in("item_code", f.itemCodes(), args);
        if (!f.excludedStatuses().isEmpty()) {
            sql += " AND (order_status IS NULL OR order_status NOT IN (" + placeholders(f.excludedStatuses()) + "))";
            args.addAll(f.excludedStatuses());
        }
        return sql;
    }

    private static String in(String column, List<String> values, List<Object> args) {
        if (values.isEmpty()) {
            return "";
        }
        args.addAll(values);
        return " AND " + column + " IN (" + placeholders(values) + ")";
    }

    private static String placeholders(List<?> values) {
        return String.join(",", Collections.nCopies(values.size(), "?"));
    }

    private static <T> List<T> query(String sql, List<Object> args, RowMapper<T> mapper) throws SQLException {
        try (Connection c = ConnectionUtil.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            List<T> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
            }
            return result;
        }
    }
}
