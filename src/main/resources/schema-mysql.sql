CREATE TABLE IF NOT EXISTS products (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(255),
    price INT NOT NULL,
    stock INT NOT NULL,
    image VARCHAR(1024)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS orders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT,
    unit_price INT NOT NULL DEFAULT 0,
    customer_name VARCHAR(255),
    zip_code VARCHAR(50),
    address TEXT,
    address_detail VARCHAR(255),
    building_name VARCHAR(255),
    room_number VARCHAR(100),
    phone VARCHAR(50),
    user_order_seq INT NOT NULL DEFAULT 1,
    order_number VARCHAR(64) NOT NULL DEFAULT '',
    order_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    canceled_at TIMESTAMP NULL DEFAULT NULL,
    delivery_date VARCHAR(50),
    delivery_time VARCHAR(50),
    order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL,
    INDEX idx_orders_order_date (order_date),
    INDEX idx_orders_product_id (product_id),
    INDEX idx_orders_phone_user_seq (phone, user_order_seq),
    INDEX idx_orders_order_number (order_number),
    INDEX idx_orders_order_status (order_status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS product_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    requester_name VARCHAR(255) NOT NULL,
    contact VARCHAR(255) NOT NULL,
    product_name VARCHAR(255),
    note TEXT,
    reference_image VARCHAR(1024),
    request_status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    request_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    handled_at TIMESTAMP NULL DEFAULT NULL,
    INDEX idx_product_requests_date (request_date),
    INDEX idx_product_requests_status (request_status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS category_settings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_name VARCHAR(255) NOT NULL,
    display_order INT NOT NULL DEFAULT 100,
    UNIQUE KEY uk_category_settings_name (category_name),
    INDEX idx_category_settings_order (display_order)
) ENGINE=InnoDB;

ALTER TABLE orders ADD COLUMN IF NOT EXISTS user_order_seq INT NOT NULL DEFAULT 1;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS order_number VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS order_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMP NULL DEFAULT NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS unit_price INT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS address_detail VARCHAR(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS building_name VARCHAR(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS room_number VARCHAR(100);
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS requester_name VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS contact VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS product_name VARCHAR(255);
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS note TEXT;
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS reference_image VARCHAR(1024);
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS request_status VARCHAR(20) NOT NULL DEFAULT 'NEW';
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS request_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS handled_at TIMESTAMP NULL DEFAULT NULL;
ALTER TABLE category_settings ADD COLUMN IF NOT EXISTS display_order INT NOT NULL DEFAULT 100;

UPDATE orders o
LEFT JOIN products p ON o.product_id = p.id
SET o.unit_price = COALESCE(p.price, o.unit_price, 0)
WHERE o.unit_price = 0;

UPDATE orders
SET phone = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(phone, ''), '-', ''), ' ', ''), '(', ''), ')', ''), '+', '')
WHERE phone IS NOT NULL;
