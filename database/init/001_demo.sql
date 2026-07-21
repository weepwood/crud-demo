CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    email VARCHAR(180) NOT NULL UNIQUE,
    phone VARCHAR(40),
    profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    description TEXT,
    price NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    stock INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
    tags TEXT[] NOT NULL DEFAULT '{}',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customers(id),
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    shipping_address JSONB,
    ordered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS order_items (
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (order_id, product_id)
);

-- Deliberately has no primary key, used to demonstrate ctid fallback.
CREATE TABLE IF NOT EXISTS notes_without_pk (
    category VARCHAR(40),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO customers (name, email, phone, profile)
VALUES
    ('Ada Chen', 'ada@example.com', '13800000001', '{"tier":"gold","language":"zh-CN"}'),
    ('Lin Wang', 'lin@example.com', '13800000002', '{"tier":"standard","language":"zh-CN"}')
ON CONFLICT (email) DO NOTHING;

INSERT INTO products (sku, name, description, price, stock, tags, metadata)
VALUES
    ('KB-001', 'Mechanical Keyboard', 'Hot-swappable compact keyboard', 699.00, 24, ARRAY['keyboard','office'], '{"switch":"linear"}'),
    ('MS-002', 'Wireless Mouse', 'Ergonomic multi-device mouse', 329.00, 50, ARRAY['mouse','wireless'], '{"dpi":4000}'),
    ('MN-003', '27-inch Monitor', '4K IPS monitor', 2399.00, 12, ARRAY['monitor','4k'], '{"ports":["HDMI","DP","USB-C"]}')
ON CONFLICT (sku) DO NOTHING;

INSERT INTO notes_without_pk (category, content)
SELECT 'demo', 'This table intentionally has no primary key.'
WHERE NOT EXISTS (SELECT 1 FROM notes_without_pk);
