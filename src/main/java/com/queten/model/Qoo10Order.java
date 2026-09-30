package com.queten.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Qoo10 주문 1건 (주문번호 단위).
 *
 * @param orderDate   주문일시 (JST)
 * @param amount      주문 금액 합계 (JPY)
 * @param orderStatus QAPI가 준 상태값 그대로. 취소/반품 제외는 집계할 때 설정(qoo10.excluded.statuses)으로 한다.
 */
public record Qoo10Order(
        String orderNo,
        LocalDateTime orderDate,
        String itemCode,
        int quantity,
        BigDecimal amount,
        String orderStatus) {
}
