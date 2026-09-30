package com.queten.dashboard;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 지표 계산 규칙을 한곳에: 분모가 0이면 0, 결과는 소수점 2자리 반올림. */
final class Metrics {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private Metrics() {
    }

    /** CTR(%) = 링크클릭 ÷ 노출 × 100 */
    static BigDecimal ctr(long linkClicks, long impressions) {
        return percent(BigDecimal.valueOf(linkClicks), BigDecimal.valueOf(impressions));
    }

    /** CPM = 광고비 ÷ 노출 × 1000 */
    static BigDecimal cpm(BigDecimal spend, long impressions) {
        return divide(spend.multiply(THOUSAND), BigDecimal.valueOf(impressions));
    }

    /** 구매전환율(%) = Qoo10 주문수 ÷ Meta 링크클릭 × 100 */
    static BigDecimal conversionRate(long orders, long linkClicks) {
        return percent(BigDecimal.valueOf(orders), BigDecimal.valueOf(linkClicks));
    }

    /** ROAS(%) = Qoo10 매출(JPY) ÷ 광고비(JPY 환산) × 100 */
    static BigDecimal roas(BigDecimal revenueJpy, BigDecimal spendJpy) {
        return percent(revenueJpy, spendJpy);
    }

    /** CPA = 광고비 ÷ Qoo10 주문수 (광고비 통화 기준) */
    static BigDecimal cpa(BigDecimal spend, long orders) {
        return divide(spend, BigDecimal.valueOf(orders));
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return divide(numerator.multiply(HUNDRED), denominator);
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }
}
