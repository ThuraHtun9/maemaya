CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(255),
    price INTEGER NOT NULL,
    stock INTEGER NOT NULL,
    image VARCHAR(1024)
);

CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT,
    unit_price INTEGER NOT NULL DEFAULT 0,
    customer_name VARCHAR(255),
    zip_code VARCHAR(50),
    address TEXT,
    address_detail VARCHAR(255),
    building_name VARCHAR(255),
    room_number VARCHAR(100),
    phone VARCHAR(50),
    user_order_seq INTEGER NOT NULL DEFAULT 1,
    order_number VARCHAR(64) NOT NULL DEFAULT '',
    order_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    canceled_at TIMESTAMP NULL,
    delivery_date VARCHAR(50),
    delivery_time VARCHAR(50),
    order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS product_requests (
    id BIGSERIAL PRIMARY KEY,
    requester_name VARCHAR(255) NOT NULL,
    contact VARCHAR(255) NOT NULL,
    product_name VARCHAR(255),
    note TEXT,
    reference_image VARCHAR(1024),
    request_status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    request_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    handled_at TIMESTAMP NULL
);

CREATE TABLE IF NOT EXISTS category_settings (
    id BIGSERIAL PRIMARY KEY,
    category_name VARCHAR(255) NOT NULL UNIQUE,
    display_order INTEGER NOT NULL DEFAULT 100
);

ALTER TABLE orders ADD COLUMN IF NOT EXISTS user_order_seq INTEGER NOT NULL DEFAULT 1;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS order_number VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS order_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS canceled_at TIMESTAMP NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS unit_price INTEGER NOT NULL DEFAULT 0;
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
ALTER TABLE product_requests ADD COLUMN IF NOT EXISTS handled_at TIMESTAMP NULL;
ALTER TABLE category_settings ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 100;
ALTER TABLE category_settings ADD COLUMN IF NOT EXISTS category_name_en VARCHAR(255);
ALTER TABLE category_settings ADD COLUMN IF NOT EXISTS category_name_ja VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_orders_order_date ON orders(order_date);
CREATE INDEX IF NOT EXISTS idx_orders_product_id ON orders(product_id);
CREATE INDEX IF NOT EXISTS idx_orders_phone_user_seq ON orders(phone, user_order_seq);
CREATE INDEX IF NOT EXISTS idx_orders_order_number ON orders(order_number);
CREATE INDEX IF NOT EXISTS idx_orders_order_status ON orders(order_status);
CREATE INDEX IF NOT EXISTS idx_product_requests_date ON product_requests(request_date);
CREATE INDEX IF NOT EXISTS idx_product_requests_status ON product_requests(request_status);
CREATE INDEX IF NOT EXISTS idx_category_settings_order ON category_settings(display_order);

UPDATE orders o
SET unit_price = COALESCE(p.price, o.unit_price, 0)
FROM products p
WHERE o.product_id = p.id
  AND o.unit_price = 0;

UPDATE orders
SET phone = REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(phone, ''), '-', ''), ' ', ''), '(', ''), ')', ''), '+', '')
WHERE phone IS NOT NULL;
