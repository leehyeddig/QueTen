package com.queten.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Meta Insights 캠페인 x 일자 1행. spend는 광고계정 통화(currency) 기준. */
public record MetaCampaignDaily(
        LocalDate statDate,
        String campaignId,
        String campaignName,
        String currency,
        BigDecimal spend,
        long impressions,
        long reach,
        long clicks,
        long linkClicks) {
}
