package com.queten.dashboard;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.queten.config.AppConfig;
import com.queten.dao.CollectStatusDao;
import com.queten.dao.DashboardDao;
import com.queten.dao.DashboardDao.CampaignTotal;
import com.queten.dao.DashboardDao.Filter;
import com.queten.dao.DashboardDao.MetaDay;
import com.queten.dao.DashboardDao.OrderDay;
import com.queten.dashboard.DashboardResponse.Campaign;
import com.queten.dashboard.DashboardResponse.Daily;
import com.queten.dashboard.DashboardResponse.Product;
import com.queten.dashboard.DashboardResponse.Summary;

/** DB 합계를 날짜 기준으로 합쳐 대시보드 지표를 계산한다. */
public class DashboardService {

    private static final String JPY = "JPY";

    private final AppConfig config;
    private final ZoneId zone;
    private final DashboardDao dao = new DashboardDao();
    private final CollectStatusDao statusDao = new CollectStatusDao();

    public DashboardService(AppConfig config) {
        this.config = config;
        this.zone = config.zone();
    }

    /**
     * @param productCode null이면 설정의 광고 상품 전체(qoo10.ad.product.codes).
     *                    지정하면 그 상품 주문만 보고, product.{코드}.campaign.ids 가 있으면 광고비도 그 캠페인만 쓴다.
     */
    public DashboardResponse build(LocalDate from, LocalDate to, String productCode) throws SQLException {
        List<String> adProducts = config.getList("qoo10.ad.product.codes");
        List<String> itemCodes = productCode == null ? adProducts : List.of(productCode);
        List<String> campaignIds = productCode == null
                ? List.of()
                : config.getList("product." + productCode + ".campaign.ids");
        Filter filter = new Filter(from, to, campaignIds, itemCodes, config.getList("qoo10.excluded.statuses"));

        String currency = spendCurrency(filter);
        BigDecimal fxToJpy = fxToJpy(currency);

        Map<LocalDate, MetaDay> metaByDate = dao.metaDaily(filter).stream()
                .collect(Collectors.toMap(MetaDay::date, Function.identity()));
        Map<LocalDate, OrderDay> ordersByDate = dao.orderDaily(filter).stream()
                .collect(Collectors.toMap(OrderDay::date, Function.identity()));

        // 데이터가 없는 날도 0으로 채워 차트 x축이 끊기지 않게 한다.
        List<Daily> daily = new ArrayList<>();
        BigDecimal totalSpend = BigDecimal.ZERO;
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long totalImpressions = 0;
        long totalLinkClicks = 0;
        long totalOrders = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            MetaDay m = metaByDate.getOrDefault(d, new MetaDay(d, BigDecimal.ZERO, 0, 0));
            OrderDay o = ordersByDate.getOrDefault(d, new OrderDay(d, 0, 0, BigDecimal.ZERO));

            daily.add(new Daily(d.toString(),
                    Metrics.money(m.spend()), m.impressions(), m.linkClicks(),
                    Metrics.ctr(m.linkClicks(), m.impressions()),
                    o.orders(), o.revenue(),
                    Metrics.conversionRate(o.orders(), m.linkClicks()),
                    Metrics.roas(o.revenue(), m.spend().multiply(fxToJpy))));

            totalSpend = totalSpend.add(m.spend());
            totalRevenue = totalRevenue.add(o.revenue());
            totalImpressions += m.impressions();
            totalLinkClicks += m.linkClicks();
            totalOrders += o.orders();
        }

        // 비율은 일자별 비율의 평균이 아니라 기간 합계로 다시 계산한다.
        Summary summary = new Summary(
                Metrics.money(totalSpend), totalImpressions, totalLinkClicks,
                Metrics.ctr(totalLinkClicks, totalImpressions),
                Metrics.cpm(totalSpend, totalImpressions),
                totalOrders, totalRevenue,
                Metrics.conversionRate(totalOrders, totalLinkClicks),
                Metrics.roas(totalRevenue, totalSpend.multiply(fxToJpy)),
                Metrics.cpa(totalSpend, totalOrders));

        List<Campaign> campaigns = new ArrayList<>();
        for (CampaignTotal c : dao.campaigns(filter)) {
            String name = c.campaignName() != null ? c.campaignName() : c.campaignId();
            campaigns.add(new Campaign(name, Metrics.money(c.spend()), c.impressions(), c.linkClicks(),
                    Metrics.ctr(c.linkClicks(), c.impressions())));
        }

        List<Product> products = dao.products(filter).stream()
                .map(p -> new Product(p.itemCode(), p.orders(), p.quantity(), p.revenue()))
                .toList();

        return new DashboardResponse(lastUpdated(), collectErrors(), currency, adProducts, summary, daily,
                campaigns, products);
    }

    /** 기간 내 광고비 통화. 데이터가 없으면 null. 여러 통화가 섞이면 합산할 수 없으므로 오류. */
    private String spendCurrency(Filter filter) throws SQLException {
        List<String> currencies = dao.spendCurrencies(filter);
        if (currencies.size() > 1) {
            throw new IllegalStateException("광고비 통화가 여러 개입니다: " + currencies);
        }
        return currencies.isEmpty() ? null : currencies.get(0);
    }

    /** 1 (광고비 통화) = ? JPY. config의 fx.{통화 소문자} 값. */
    private BigDecimal fxToJpy(String currency) {
        if (currency == null || JPY.equals(currency)) {
            return BigDecimal.ONE;
        }
        String key = "fx." + currency.toLowerCase(Locale.ROOT);
        BigDecimal rate = config.getDecimal(key);
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalStateException("환율 설정이 필요합니다: " + key + " (1 " + currency + " = ? JPY)");
        }
        return rate;
    }

    private Map<String, String> lastUpdated() throws SQLException {
        Map<String, LocalDateTime> all = statusDao.lastSuccessAll();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("meta", format(all.get("META")));
        result.put("qoo10", format(all.get("QOO10")));
        return result;
    }

    private Map<String, String> collectErrors() throws SQLException {
        Map<String, String> all = statusDao.lastErrorAll();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("meta", all.get("META"));
        result.put("qoo10", all.get("QOO10"));
        return result;
    }

    private String format(LocalDateTime t) {
        return t == null
                ? null
                : t.truncatedTo(ChronoUnit.SECONDS).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
