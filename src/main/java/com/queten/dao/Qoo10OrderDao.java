package com.queten.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import com.queten.db.ConnectionUtil;
import com.queten.model.Qoo10Order;

public class Qoo10OrderDao {

    // first_collected_at은 처음 저장할 때만 기록하고, 이후에는 상태·수량·금액 등만 갱신한다.
    private static final String UPSERT = """
            INSERT INTO qoo10_order
                (order_no, order_date, order_ymd, item_code, quantity, amount, order_status,
                 first_collected_at, last_collected_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                order_date        = VALUES(order_date),
                order_ymd         = VALUES(order_ymd),
                item_code         = VALUES(item_code),
                quantity          = VALUES(quantity),
                amount            = VALUES(amount),
                order_status      = VALUES(order_status),
                last_collected_at = VALUES(last_collected_at)
            """;

    /** 주문번호 기준 UPSERT. 한 트랜잭션으로 처리해 실패하면 기존 데이터가 그대로 남는다. */
    public int upsert(List<Qoo10Order> orders, LocalDateTime collectedAt) throws SQLException {
        if (orders.isEmpty()) {
            return 0;
        }
        try (Connection c = ConnectionUtil.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (Qoo10Order o : orders) {
                    ps.setString(1, o.orderNo());
                    ps.setObject(2, o.orderDate());
                    ps.setObject(3, o.orderDate().toLocalDate());
                    ps.setString(4, o.itemCode());
                    ps.setInt(5, o.quantity());
                    ps.setBigDecimal(6, o.amount());
                    ps.setString(7, o.orderStatus());
                    ps.setObject(8, collectedAt);
                    ps.setObject(9, collectedAt);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
        return orders.size();
    }
}
