package com.myshop.springshop.repository;

import com.myshop.springshop.model.ProductRequestRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ProductRequestRepository {

    private static final RowMapper<ProductRequestRecord> REQUEST_ROW_MAPPER = (rs, rowNum) -> new ProductRequestRecord(
            rs.getLong("id"),
            rs.getString("requester_name"),
            rs.getString("contact"),
            rs.getString("product_name"),
            rs.getString("note"),
            rs.getString("reference_image"),
            rs.getString("request_status"),
            rs.getString("request_date")
    );

    private final JdbcTemplate jdbcTemplate;

    public ProductRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void add(String requesterName, String contact, String productName, String note, String referenceImage) {
        jdbcTemplate.update(
                """
                INSERT INTO product_requests (requester_name, contact, product_name, note, reference_image)
                VALUES (?, ?, ?, ?, ?)
                """,
                requesterName,
                contact,
                productName,
                note,
                referenceImage
        );
    }

    public List<ProductRequestRecord> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id,
                       requester_name,
                       contact,
                       product_name,
                       note,
                       reference_image,
                       request_status,
                       request_date
                FROM product_requests
                ORDER BY request_date DESC
                """,
                REQUEST_ROW_MAPPER
        );
    }

    public int markDone(long requestId) {
        return jdbcTemplate.update(
                """
                UPDATE product_requests
                SET request_status = 'DONE',
                    handled_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND request_status = 'NEW'
                """,
                requestId
        );
    }

    public int delete(long requestId) {
        return jdbcTemplate.update("DELETE FROM product_requests WHERE id = ?", requestId);
    }
}
