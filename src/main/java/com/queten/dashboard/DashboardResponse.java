package com.queten.dashboard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * /api/dashboard 응답. 비율(ctr, conversionRate, roas)은 % 값이고 소수점 2자리.
 * spend, cpm, cpa는 광고계정 통화(spendCurrency) 기준, revenueJpy는 JPY.
 * collectErrors는 마지막 수집이 실패한 소스의 사유 (정상이면 null).
 */
public record DashboardResponse(
        Map<String, String> lastUpdated,
        Map<String, String> collectErrors,
        String spendCurrency,
        List<String> productCodes,
        Summary summary,
        List<Daily> daily,
        List<Campaign> campaigns,
        List<Product> products) {

    public record Summary(BigDecimal spend, long impressions, long linkClicks, BigDecimal ctr, BigDecimal cpm,
            long orders, BigDecimal revenueJpy, BigDecimal conversionRate, BigDecimal roas, BigDecimal cpa) {
    }

    public record Daily(String date, BigDecimal spend, long impressions, long linkClicks, BigDecimal ctr,
            long orders, BigDecimal revenueJpy, BigDecimal conversionRate, BigDecimal roas) {
    }

    public record Campaign(String campaignName, BigDecimal spend, long impressions, long linkClicks,
            BigDecimal ctr) {
    }

    public record Product(String productCode, long orders, long quantity, BigDecimal revenueJpy) {
    }
}
