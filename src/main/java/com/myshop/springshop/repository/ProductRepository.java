package com.myshop.springshop.repository;

import com.myshop.springshop.model.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProductRepository {

    private static final RowMapper<Product> PRODUCT_ROW_MAPPER = (rs, rowNum) -> new Product(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getInt("price"),
            rs.getInt("stock"),
            rs.getString("image")
    );

    private final JdbcTemplate jdbcTemplate;

    public ProductRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Product> findAll() {
        return jdbcTemplate.query("SELECT * FROM products ORDER BY id", PRODUCT_ROW_MAPPER);
    }

    public Optional<Product> findById(long id) {
        List<Product> result = jdbcTemplate.query("SELECT * FROM products WHERE id = ?", PRODUCT_ROW_MAPPER, id);
        return result.stream().findFirst();
    }

    public List<String> findDistinctCategories() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT category FROM products WHERE category IS NOT NULL AND TRIM(category) != '' ORDER BY category",
                String.class
        );
    }

    public void add(String name, String category, int price, int stock, String image) {
        jdbcTemplate.update(
                "INSERT INTO products (name, category, price, stock, image) VALUES (?, ?, ?, ?, ?)",
                name,
                category,
                price,
                stock,
                image
        );
    }

    public void updateProduct(long productId, String name, String category, int price, int stock, String image) {
        jdbcTemplate.update(
                "UPDATE products SET name = ?, category = ?, price = ?, stock = ?, image = ? WHERE id = ?",
                name,
                category,
                price,
                stock,
                image,
                productId
        );
    }

    public void delete(long productId) {
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
    }

    public int decrementStockIfAvailable(long productId) {
        return jdbcTemplate.update(
                "UPDATE products SET stock = stock - 1 WHERE id = ? AND stock > 0",
                productId
        );
    }

    public void incrementStock(long productId, int quantity) {
        jdbcTemplate.update(
                "UPDATE products SET stock = stock + ? WHERE id = ?",
                quantity,
                productId
        );
    }

    public long count() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class);
        return count == null ? 0L : count;
    }

    public Map<String, Integer> findCategoryDisplayOrderMap() {
        Map<String, Integer> categoryOrderMap = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT category_name, display_order FROM category_settings"
        );
        for (Map<String, Object> row : rows) {
            Object categoryName = row.get("category_name");
            Object displayOrder = row.get("display_order");
            if (categoryName == null || displayOrder == null) {
                continue;
            }
            categoryOrderMap.put(
                    String.valueOf(categoryName),
                    ((Number) displayOrder).intValue()
            );
        }
        return categoryOrderMap;
    }

    public void saveCategoryDisplayOrder(String categoryName, int displayOrder) {
        int updated = jdbcTemplate.update(
                "UPDATE category_settings SET display_order = ? WHERE category_name = ?",
                displayOrder,
                categoryName
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO category_settings (category_name, display_order) VALUES (?, ?)",
                    categoryName,
                    displayOrder
            );
        }
    }

    public void addCategory(String categoryName) {
        Integer nextOrder = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(display_order), 0) + 10 FROM category_settings",
                Integer.class
        );
        saveCategoryDisplayOrder(categoryName, nextOrder == null ? 10 : nextOrder);
    }

    public void ensureCategoryExists(String categoryName) {
        if (countCategorySetting(categoryName) > 0) {
            return;
        }
        addCategory(categoryName);
    }

    public void updateCategory(String originalName, String newName, int displayOrder) {
        boolean sameName = originalName.equals(newName);

        if (!sameName) {
            jdbcTemplate.update(
                    "UPDATE products SET category = ? WHERE category = ?",
                    newName,
                    originalName
            );
        }

        if (!sameName && countCategorySetting(newName) > 0) {
            saveCategoryDisplayOrder(newName, displayOrder);
            jdbcTemplate.update(
                    "DELETE FROM category_settings WHERE category_name = ?",
                    originalName
            );
            return;
        }

        int updated = jdbcTemplate.update(
                "UPDATE category_settings SET category_name = ?, display_order = ? WHERE category_name = ?",
                newName,
                displayOrder,
                originalName
        );

        if (updated == 0) {
            saveCategoryDisplayOrder(newName, displayOrder);
        }

        if (!sameName && countCategorySetting(originalName) > 0) {
            jdbcTemplate.update(
                    "DELETE FROM category_settings WHERE category_name = ?",
                    originalName
            );
        }
    }

    public void deleteCategory(String categoryName) {
        jdbcTemplate.update(
                "UPDATE products SET category = NULL WHERE category = ?",
                categoryName
        );
        jdbcTemplate.update(
                "DELETE FROM category_settings WHERE category_name = ?",
                categoryName
        );
    }

    private int countCategorySetting(String categoryName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category_settings WHERE category_name = ?",
                Integer.class,
                categoryName
        );
        return count == null ? 0 : count;
    }
}
