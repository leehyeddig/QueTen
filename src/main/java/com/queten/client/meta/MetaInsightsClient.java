package com.queten.client.meta;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.queten.config.AppConfig;
import com.queten.model.MetaCampaignDaily;

/**
 * Meta Marketing API Insights 조회 (캠페인 x 일자).
 * GET /{version}/act_{ad_account_id}/insights?level=campaign&time_increment=1
 */
public class MetaInsightsClient {

    private static final Logger LOG = Logger.getLogger(MetaInsightsClient.class.getName());

    private static final String FIELDS = String.join(",",
            "campaign_id", "campaign_name", "account_currency",
            "spend", "impressions", "reach", "clicks", "inline_link_clicks");
    private static final int PAGE_LIMIT = 500;
    /** Meta 오류코드 190 = 액세스 토큰 만료·무효 */
    private static final String CODE_INVALID_TOKEN = "190";
    /** Meta 오류코드 200 = 권한 없음·API 접근 차단 (앱/계정 쪽 제한) */
    private static final String CODE_PERMISSION = "200";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiVersion;
    private final String adAccountId;
    private final String accessToken;

    public MetaInsightsClient(AppConfig config) {
        this.apiVersion = config.get("meta.api.version");
        String account = config.get("meta.ad.account.id");
        this.adAccountId = (account != null && account.startsWith("act_")) ? account.substring(4) : account;
        this.accessToken = config.get("meta.access.token");
    }

    /** 호출에 필요한 설정이 모두 있으면 null, 아니면 빠진 항목 설명. */
    public String notReadyReason() {
        List<String> missing = new ArrayList<>();
        if (apiVersion == null) {
            missing.add("meta.api.version");
        }
        if (adAccountId == null) {
            missing.add("meta.ad.account.id");
        }
        if (accessToken == null) {
            missing.add("meta.access.token");
        }
        return missing.isEmpty() ? null : "설정 누락: " + missing;
    }

    /** from~to(포함) 기간의 캠페인별 일자 데이터. 페이지를 끝까지 따라간다. */
    public List<MetaCampaignDaily> fetchDaily(LocalDate from, LocalDate to)
            throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("level", "campaign");
        params.put("time_increment", "1");
        params.put("time_range", "{\"since\":\"" + from + "\",\"until\":\"" + to + "\"}");
        params.put("fields", FIELDS);
        params.put("limit", String.valueOf(PAGE_LIMIT));
        params.put("access_token", accessToken);

        String url = "https://graph.facebook.com/" + apiVersion + "/act_" + adAccountId + "/insights?"
                + encode(params);

        List<MetaCampaignDaily> rows = new ArrayList<>();
        while (url != null) {
            JsonObject body = get(url);
            for (JsonElement e : body.getAsJsonArray("data")) {
                rows.add(toRow(e.getAsJsonObject()));
            }
            url = nextPageUrl(body);
        }
        return rows;
    }

    // URL에 access_token이 들어 있으므로 URL은 절대 로그에 남기지 않는다.
    private JsonObject get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        JsonObject body;
        try {
            body = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new MetaApiException("Meta 응답을 해석할 수 없습니다 (HTTP " + res.statusCode() + ")", e);
        }

        if (body.has("error")) {
            JsonObject err = body.getAsJsonObject("error");
            String msg = String.format("Meta API 오류: HTTP %d, code=%s, subcode=%s, type=%s, message=%s, fbtrace_id=%s",
                    res.statusCode(), str(err, "code"), str(err, "error_subcode"), str(err, "type"),
                    str(err, "message"), str(err, "fbtrace_id"));
            if (CODE_INVALID_TOKEN.equals(str(err, "code"))) {
                msg += " → 액세스 토큰이 만료됐거나 무효입니다. 새 토큰을 meta.access.token에 넣고 서버를 재시작하세요.";
            } else if (CODE_PERMISSION.equals(str(err, "code"))) {
                msg += " → Meta가 이 앱/토큰의 API 접근을 막았습니다 (코드로 해결 불가)."
                        + " developers.facebook.com 앱 대시보드에서 앱 제한·비활성 여부와 Marketing API(ads_read) 권한을,"
                        + " 비즈니스 관리자에서 광고계정·사용자 계정 제한 여부를 확인하고, 해제 후 토큰을 새로 발급해 meta.access.token에 넣으세요.";
            }
            LOG.warning(msg);
            throw new MetaApiException(msg);
        }
        if (res.statusCode() != 200 || !body.has("data")) {
            throw new MetaApiException("Meta API 비정상 응답: HTTP " + res.statusCode());
        }
        return body;
    }

    private static String nextPageUrl(JsonObject body) {
        if (!body.has("paging")) {
            return null;
        }
        JsonObject paging = body.getAsJsonObject("paging");
        return paging.has("next") ? paging.get("next").getAsString() : null;
    }

    // Insights는 숫자도 문자열로 주고, 값이 0인 지표는 필드 자체가 빠질 수 있다.
    private static MetaCampaignDaily toRow(JsonObject o) {
        return new MetaCampaignDaily(
                LocalDate.parse(str(o, "date_start")),
                str(o, "campaign_id"),
                str(o, "campaign_name"),
                str(o, "account_currency"),
                decimal(o, "spend"),
                lng(o, "impressions"),
                lng(o, "reach"),
                lng(o, "clicks"),
                lng(o, "inline_link_clicks"));
    }

    private static String str(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static BigDecimal decimal(JsonObject o, String name) {
        String v = str(o, name);
        return v == null ? BigDecimal.ZERO : new BigDecimal(v);
    }

    private static long lng(JsonObject o, String name) {
        String v = str(o, name);
        return v == null ? 0L : Long.parseLong(v);
    }

    private static String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
