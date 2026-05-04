package com.myshop.springshop.repository;

import com.myshop.springshop.model.OrderRecord;
import com.myshop.springshop.model.OrderRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {

    private static final RowMapper<OrderRecord> ORDER_ROW_MAPPER = (rs, rowNum) -> new OrderRecord(
            rs.getString("order_number"),
            rs.getString("order_date"),
            rs.getString("customer_name"),
            rs.getString("phone"),
            rs.getString("zip_code"),
            rs.getString("address"),
            rs.getString("address_detail"),
            rs.getString("building_name"),
            rs.getString("room_number"),
            getNullableLong(rs, "product_id"),
            rs.getString("product_name"),
            rs.getInt("product_price"),
            rs.getString("delivery_date"),
            rs.getString("delivery_time"),
            rs.getString("order_status")
    );

    private static final RowMapper<OrderSummary> ORDER_SUMMARY_ROW_MAPPER = (rs, rowNum) -> new OrderSummary(
            rs.getString("order_number"),
            rs.getString("order_date"),
            rs.getString("customer_name"),
            rs.getString("phone"),
            rs.getString("delivery_date"),
            rs.getString("delivery_time"),
            rs.getString("order_status")
    );

    private static final RowMapper<OrderProductCount> ORDER_PRODUCT_COUNT_ROW_MAPPER = (rs, rowNum) -> new OrderProductCount(
            rs.getLong("product_id"),
            rs.getInt("quantity")
    );

    private static final RowMapper<OrderItemSummary> ORDER_ITEM_SUMMARY_ROW_MAPPER = (rs, rowNum) -> new OrderItemSummary(
            rs.getLong("product_id"),
            rs.getString("product_name"),
            rs.getInt("quantity")
    );

    private static final RowMapper<ReceiptItemRow> RECEIPT_ITEM_ROW_MAPPER = (rs, rowNum) -> new ReceiptItemRow(
            rs.getLong("product_id"),
            rs.getString("product_name"),
            rs.getString("product_image"),
            rs.getInt("unit_price"),
            rs.getInt("quantity")
    );

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void add(long productId, OrderRequest request, int userOrderSeq, String orderNumber) {
        jdbcTemplate.update(
                """
                INSERT INTO orders (
                    product_id,
                    customer_name,
                    zip_code,
                    address,
                    address_detail,
                    building_name,
                    room_number,
                    phone,
                    delivery_date,
                    delivery_time,
                    unit_price,
                    user_order_seq,
                    order_number,
                    order_status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE((SELECT price FROM products WHERE id = ?), 0), ?, ?, 'ACTIVE')
                """,
                productId,
                request.getCustomerName(),
                request.getZipCode(),
                request.getAddress(),
                request.getAddressDetail(),
                request.getBuildingName(),
                request.getRoomNumber(),
                request.getPhone(),
                request.getDeliveryDate(),
                request.getDeliveryTime(),
                productId,
                userOrderSeq,
                orderNumber
        );
    }

    public Optional<String> findTodayActiveOrderNumberForMerge(
            String phone,
            String customerName,
            String deliveryDate,
            String deliveryTime
    ) {
        List<String> rows = jdbcTemplate.queryForList(
                """
                SELECT order_number
                FROM orders
                WHERE phone = ?
                  AND order_status = 'ACTIVE'
                  AND DATE(order_date) = DATE(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Tokyo')
                  AND LOWER(TRIM(COALESCE(customer_name, ''))) = LOWER(TRIM(?))
                  AND COALESCE(delivery_date, '') = ?
                  AND COALESCE(delivery_time, '') = ?
                ORDER BY id DESC
                LIMIT 1
                """,
                String.class,
                phone,
                customerName,
                deliveryDate,
                deliveryTime
        );
        return rows.stream().findFirst();
    }

    public Optional<OrderSummary> findOrderSummary(String orderNumber, String phone) {
        List<OrderSummary> rows = jdbcTemplate.query(
                """
                SELECT order_number,
                       MIN(order_date) AS order_date,
                       MIN(customer_name) AS customer_name,
                       MIN(phone) AS phone,
                       MIN(delivery_date) AS delivery_date,
                       MIN(delivery_time) AS delivery_time,
                       CASE
                           WHEN SUM(CASE WHEN order_status = 'ACTIVE' THEN 1 ELSE 0 END) > 0 THEN 'ACTIVE'
                           WHEN SUM(CASE WHEN order_status = 'READY' THEN 1 ELSE 0 END) > 0 THEN 'READY'
                           WHEN SUM(CASE WHEN order_status = 'DELIVERED' THEN 1 ELSE 0 END) > 0 THEN 'DELIVERED'
                           ELSE 'CANCELED'
                       END AS order_status
                FROM orders
                WHERE order_number = ?
                  AND phone = ?
                GROUP BY order_number
                """,
                ORDER_SUMMARY_ROW_MAPPER,
                orderNumber,
                phone
        );
        return rows.stream().findFirst();
    }

    public List<OrderSummary> findOrderSummariesByCustomer(String customerName, String phone, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return jdbcTemplate.query(
                """
                SELECT order_number,
                       MIN(order_date) AS order_date,
                       MIN(customer_name) AS customer_name,
                       MIN(phone) AS phone,
                       MIN(delivery_date) AS delivery_date,
                       MIN(delivery_time) AS delivery_time,
                       CASE
                           WHEN SUM(CASE WHEN order_status = 'ACTIVE' THEN 1 ELSE 0 END) > 0 THEN 'ACTIVE'
                           WHEN SUM(CASE WHEN order_status = 'READY' THEN 1 ELSE 0 END) > 0 THEN 'READY'
                           WHEN SUM(CASE WHEN order_status = 'DELIVERED' THEN 1 ELSE 0 END) > 0 THEN 'DELIVERED'
                           ELSE 'CANCELED'
                       END AS order_status
                FROM orders
                WHERE phone = ?
                  AND LOWER(TRIM(COALESCE(customer_name, ''))) = LOWER(TRIM(?))
                GROUP BY order_number
                ORDER BY MIN(order_date) DESC
                LIMIT ?
                """,
                ORDER_SUMMARY_ROW_MAPPER,
                phone,
                customerName,
                safeLimit
        );
    }

    public int markOrderReady(String orderNumber, String phone) {
        return jdbcTemplate.update(
                """
                UPDATE orders
                SET order_status = 'READY'
                WHERE order_number = ?
                  AND phone = ?
                  AND order_status = 'ACTIVE'
                """,
                orderNumber,
                phone
        );
    }

    public int markOrderDelivered(String orderNumber, String phone) {
        return jdbcTemplate.update(
                """
                UPDATE orders
                SET order_status = 'DELIVERED'
                WHERE order_number = ?
                  AND phone = ?
                  AND order_status = 'READY'
                """,
                orderNumber,
                phone
        );
    }

    public List<OrderProductCount> findProductCountsForActiveOrder(String orderNumber, String phone) {
        return jdbcTemplate.query(
                """
                SELECT product_id, COUNT(*) AS quantity
                FROM orders
                WHERE order_number = ?
                  AND phone = ?
                  AND order_status = 'ACTIVE'
                  AND product_id IS NOT NULL
                GROUP BY product_id
                """,
                ORDER_PRODUCT_COUNT_ROW_MAPPER,
                orderNumber,
                phone
        );
    }

    public int cancelOrder(String orderNumber, String phone) {
        return jdbcTemplate.update(
                """
                UPDATE orders
                SET order_status = 'CANCELED',
                    canceled_at = CURRENT_TIMESTAMP
                WHERE order_number = ?
                  AND phone = ?
                  AND order_status = 'ACTIVE'
                """,
                orderNumber,
                phone
        );
    }

    public int deleteOrder(String orderNumber, String phone) {
        return jdbcTemplate.update(
                """
                DELETE FROM orders
                WHERE order_number = ?
                  AND phone = ?
                """,
                orderNumber,
                phone
        );
    }

    public int addItemToExistingOrder(long productId, String orderNumber, String phone) {
        return jdbcTemplate.update(
                """
                INSERT INTO orders (
                    product_id,
                    customer_name,
                    zip_code,
                    address,
                    address_detail,
                    building_name,
                    room_number,
                    phone,
                    delivery_date,
                    delivery_time,
                    unit_price,
                    user_order_seq,
                    order_number,
                    order_status
                )
                SELECT ?,
                       customer_name,
                       zip_code,
                       address,
                       address_detail,
                       building_name,
                       room_number,
                       phone,
                       delivery_date,
                       delivery_time,
                       COALESCE((SELECT price FROM products WHERE id = ?), 0),
                       user_order_seq,
                       order_number,
                       'ACTIVE'
                FROM orders
                WHERE order_number = ?
                  AND phone = ?
                ORDER BY id
                LIMIT 1
                """,
                productId,
                productId,
                orderNumber,
                phone
        );
    }

    public List<OrderItemSummary> findActiveOrderItems(String orderNumber, String phone) {
        return jdbcTemplate.query(
                """
                SELECT orders.product_id,
                       COALESCE(products.name, 'Deleted product') AS product_name,
                       COUNT(*) AS quantity
                FROM orders
                LEFT JOIN products ON orders.product_id = products.id
                WHERE orders.order_number = ?
                  AND orders.phone = ?
                  AND orders.order_status IN ('ACTIVE', 'READY', 'DELIVERED')
                  AND orders.product_id IS NOT NULL
                GROUP BY orders.product_id, products.name
                ORDER BY product_name
                """,
                ORDER_ITEM_SUMMARY_ROW_MAPPER,
                orderNumber,
                phone
        );
    }

    public Optional<Long> findSingleActiveOrderRowId(String orderNumber, String phone, long productId) {
        List<Long> rows = jdbcTemplate.queryForList(
                """
                SELECT id
                FROM orders
                WHERE order_number = ?
                  AND phone = ?
                  AND product_id = ?
                  AND order_status = 'ACTIVE'
                ORDER BY id
                LIMIT 1
                """,
                Long.class,
                orderNumber,
                phone,
                productId
        );
        return rows.stream().findFirst();
    }

    public int cancelSingleOrderRow(long orderRowId) {
        return jdbcTemplate.update(
                """
                UPDATE orders
                SET order_status = 'CANCELED',
                    canceled_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND order_status = 'ACTIVE'
                """,
                orderRowId
        );
    }

    public int findNextOrderSequenceByPhone(String phone) {
        Integer nextSeq = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(user_order_seq), 0) + 1
                FROM orders
                WHERE phone = ?
                """,
                Integer.class,
                phone
        );
        return nextSeq == null ? 1 : nextSeq;
    }

    public List<OrderRecord> findAllWithProduct() {
        return jdbcTemplate.query(
                """
                SELECT orders.order_number,
                       orders.order_date,
                       orders.customer_name,
                       orders.phone,
                       orders.zip_code,
                       orders.address,
                       orders.address_detail,
                       orders.building_name,
                       orders.room_number,
                       orders.product_id,
                       orders.delivery_date,
                       orders.delivery_time,
                       orders.order_status,
                       COALESCE(products.name, 'Deleted product') AS product_name,
                       COALESCE(orders.unit_price, 0) AS product_price
                FROM orders
                LEFT JOIN products ON orders.product_id = products.id
                ORDER BY orders.order_date DESC
                """,
                ORDER_ROW_MAPPER
        );
    }

    public List<ReceiptItemRow> findReceiptItems(String orderNumber, String phone) {
        return jdbcTemplate.query(
                """
                SELECT orders.product_id,
                       COALESCE(products.name, 'Deleted product') AS product_name,
                       COALESCE(products.image, '') AS product_image,
                       COALESCE(orders.unit_price, 0) AS unit_price,
                       COUNT(*) AS quantity
                FROM orders
                LEFT JOIN products ON orders.product_id = products.id
                WHERE orders.order_number = ?
                  AND orders.phone = ?
                  AND orders.order_status IN ('ACTIVE', 'READY', 'DELIVERED')
                  AND orders.product_id IS NOT NULL
                GROUP BY orders.product_id, products.name, products.image, orders.unit_price
                ORDER BY product_name
                """,
                RECEIPT_ITEM_ROW_MAPPER,
                orderNumber,
                phone
        );
    }

    public int findOrderTotal(String orderNumber, String phone) {
        Integer total = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(unit_price), 0)
                FROM orders
                WHERE order_number = ?
                  AND phone = ?
                  AND order_status IN ('ACTIVE', 'READY', 'DELIVERED')
                """,
                Integer.class,
                orderNumber,
                phone
        );
        return total == null ? 0 : total;
    }

    public record OrderSummary(
            String orderNumber,
            String orderDate,
            String customerName,
            String phone,
            String deliveryDate,
            String deliveryTime,
            String orderStatus
    ) {
    }

    public record OrderProductCount(long productId, int quantity) {
    }

    public record OrderItemSummary(long productId, String productName, int quantity) {
    }

    public record ReceiptItemRow(
            long productId,
            String productName,
            String productImage,
            int unitPrice,
            int quantity
    ) {
    }

    private static Long getNullableLong(java.sql.ResultSet rs, String columnName) throws java.sql.SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }
}
