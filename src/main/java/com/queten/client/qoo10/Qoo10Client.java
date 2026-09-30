package com.queten.client.qoo10;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.queten.config.AppConfig;
import com.queten.model.Qoo10Order;

/**
 * Qoo10 Japan QAPI 클라이언트.
 *
 * <p>명세: QSM 개발자 페이지(developer.qoo10.jp), 2026-09-24 확인
 * <ul>
 * <li>ShippingBasic.GetShippingInfo_v3 (상태별 배송정보 조회): 주문</li>
 * <li>ShippingBasic.GetClaimInfo_V3 (상태별 클레임정보 조회): 취소·반품</li>
 * </ul>
 *
 * <p>인증: QSM에서 발급한 Certification Key(판매자 인증키, 유효기간 1년)를 GiosisCertificationKey 헤더로 보낸다.
 * QAPI 목록에 인증키 발급 API가 없으므로 자동 재발급은 하지 않는다. 인증 오류가 나면 QSM에서 새 키를 받아 넣어야 한다.
 *
 * <p>응답에는 구매자·수취인 개인정보가 들어 있으므로, 필요한 필드만 꺼내고 원문은 저장하거나 로그로 남기지 않는다.
 */
public class Qoo10Client {

    private static final Logger LOG = Logger.getLogger(Qoo10Client.class.getName());

    private static final String BASE_URL = "https://api.qoo10.jp/GMKT.INC.Front.QAPIService/ebayjapan.qapi/";
    private static final String API_SHIPPING = "ShippingBasic.GetShippingInfo_v3";
    private static final String API_CLAIM = "ShippingBasic.GetClaimInfo_V3";
    /** 명세 "API 기본정보"의 버전 표기 v.1.0 */
    private static final String QAPI_VERSION = "1.0";

    private static final String SUCCESS = "0";
    /** -10000 Please check the Seller Authorization Key / -90004, -90005 Seller authorization key is expired */
    private static final Set<String> AUTH_ERROR_CODES = Set.of("-10000", "-90004", "-90005");

    /**
     * 두 API 모두 한 번에 최대 90일 (Searching period exceeds to 90 days).
     * 실제 호출 결과 시작~종료가 정확히 90일이어도 -10002가 나서(2026-09-24 확인) 89일 단위로 자른다.
     */
    private static final int MAX_RANGE_DAYS = 89;
    private static final DateTimeFormatter PARAM_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** ShippingStatus: 공백 = 1~3(배송대기·배송요청·배송준비), 4 = 배송중, 5 = 배송완료 */
    private static final List<String> SHIPPING_STATUSES = List.of("", "4", "5");
    /** SearchCondition 1 = 주문일 */
    private static final String SHIPPING_BY_ORDER_DATE = "1";

    /** 매출에서 빠지는 완료 클레임 ClaimStat: 3 취소완료, 6 반품완료, 14 미수취 환불 완료, 16 미납주문 취소 */
    private static final List<String> COMPLETED_CLAIM_STATS = List.of("3", "6", "14", "16");
    /** search_condition 3 = 취소/환불 완료일. 예전 주문이 최근에 취소돼도 잡히도록 완료일 기준으로 조회한다. */
    private static final String CLAIM_BY_COMPLETED_DATE = "3";
    /** 클레임 주문의 orderStatus 값 = "CLAIM_" + ClaimStat 코드 (예: CLAIM_3) */
    public static final String CLAIM_STATUS_PREFIX = "CLAIM_";

    private static final List<DateTimeFormatter> RESPONSE_DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String certificationKey;

    public Qoo10Client(AppConfig config) {
        this.certificationKey = config.get("qoo10.certification.key");
    }

    /** 호출할 준비가 됐으면 null, 아니면 이유. */
    public String notReadyReason() {
        return certificationKey == null ? "설정 누락: qoo10.certification.key (QSM 개발자 페이지의 Certification Key)" : null;
    }

    /**
     * from~to(주문일 기준) 주문과, 같은 기간에 완료된 취소·반품을 합쳐 돌려준다.
     * 90일 단위로 잘라 호출하고, 같은 주문은 주문번호 기준으로 하나만 남긴다. 클레임 상태가 배송 상태보다 우선한다.
     */
    public List<Qoo10Order> fetchOrders(LocalDateTime from, LocalDateTime to)
            throws IOException, InterruptedException {
        String reason = notReadyReason();
        if (reason != null) {
            throw new IllegalStateException(reason);
        }

        Map<String, Qoo10Order> orders = new LinkedHashMap<>();
        Map<String, Qoo10Order> claims = new LinkedHashMap<>();
        LocalDateTime windowStart = from;
        while (windowStart.isBefore(to)) {
            LocalDateTime windowEnd = windowStart.plusDays(MAX_RANGE_DAYS);
            if (windowEnd.isAfter(to)) {
                windowEnd = to;
            }
            for (String status : SHIPPING_STATUSES) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("ShippingStatus", status);
                params.put("SearchStartDate", windowStart.format(PARAM_DATE));
                params.put("SearchEndDate", windowEnd.format(PARAM_DATE));
                params.put("SearchCondition", SHIPPING_BY_ORDER_DATE);
                for (JsonElement row : call(API_SHIPPING, params)) {
                    Qoo10Order o = fromShipping(row.getAsJsonObject());
                    orders.put(o.orderNo(), o);
                }
            }
            for (String claimStat : COMPLETED_CLAIM_STATS) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("ClaimStat", claimStat);
                params.put("search_Sdate", windowStart.format(PARAM_DATE));
                params.put("search_Edate", windowEnd.format(PARAM_DATE));
                params.put("search_condition", CLAIM_BY_COMPLETED_DATE);
                for (JsonElement row : call(API_CLAIM, params)) {
                    Qoo10Order o = fromClaim(row.getAsJsonObject(), claimStat);
                    claims.put(o.orderNo(), o);
                }
            }
            windowStart = windowEnd;
        }
        orders.putAll(claims);
        return new ArrayList<>(orders.values());
    }

    /** API 1회 호출. 결과코드가 성공(0)이 아니면 코드와 메시지를 로그로 남기고 예외. */
    private JsonArray call(String apiMethod, Map<String, String> params) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("returnType", "application/json");
        form.putAll(params);

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + apiMethod))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("QAPIVersion", QAPI_VERSION)
                .header("GiosisCertificationKey", certificationKey)
                .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (res.statusCode() != 200) {
            LOG.warning("Qoo10 " + apiMethod + " HTTP " + res.statusCode());
            throw new Qoo10ApiException(apiMethod, "HTTP " + res.statusCode(), null);
        }

        JsonObject body;
        try {
            body = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new Qoo10ApiException(apiMethod, "PARSE", "응답을 JSON으로 해석할 수 없습니다");
        }

        String code = str(body, "ResultCode");
        String message = str(body, "ResultMsg");
        if (!SUCCESS.equals(code)) {
            if (AUTH_ERROR_CODES.contains(code)) {
                message += " → Certification Key가 잘못됐거나 만료됐습니다. QSM 개발자 페이지에서 새 키를 받아"
                        + " qoo10.certification.key에 넣고 서버를 재시작하세요.";
            }
            LOG.warning("Qoo10 " + apiMethod + " 실패: code=" + code + ", message=" + message);
            throw new Qoo10ApiException(apiMethod, code, message);
        }

        JsonElement result = body.get("ResultObject");
        return (result == null || !result.isJsonArray()) ? new JsonArray() : result.getAsJsonArray();
    }

    /** GetShippingInfo_v3 응답 1건. 금액은 Total(실주문금액 = 상품가격+옵션가격-할인금액). */
    private static Qoo10Order fromShipping(JsonObject row) {
        return new Qoo10Order(
                str(row, "OrderNo"),
                parseDate(str(row, "OrderDate")),
                str(row, "ItemNo"),
                intValue(row, "OrderQty"),
                decimal(row, "Total"),
                str(row, "ShippingStatus"));
    }

    /** GetClaimInfo_V3 응답 1건. 금액은 paymentAmount(결제금액). */
    private static Qoo10Order fromClaim(JsonObject row, String claimStat) {
        return new Qoo10Order(
                str(row, "orderNo"),
                parseDate(str(row, "orderDate")),
                str(row, "itemCode"),
                intValue(row, "orderQty"),
                decimal(row, "paymentAmount"),
                CLAIM_STATUS_PREFIX + claimStat);
    }

    private static LocalDateTime parseDate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("주문일이 비어 있습니다");
        }
        for (DateTimeFormatter f : RESPONSE_DATE_FORMATS) {
            try {
                return LocalDateTime.parse(value.trim(), f);
            } catch (DateTimeParseException ignored) {
                // 다음 형식 시도
            }
        }
        throw new IllegalArgumentException("주문일 형식을 해석할 수 없습니다: " + value);
    }

    private static String str(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static int intValue(JsonObject o, String name) {
        String v = str(o, name);
        return v == null ? 0 : Integer.parseInt(v.trim());
    }

    private static BigDecimal decimal(JsonObject o, String name) {
        String v = str(o, name);
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.trim());
    }

    private static String encode(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
