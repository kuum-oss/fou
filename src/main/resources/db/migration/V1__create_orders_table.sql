CREATE TABLE IF NOT EXISTS orders
(
    id         UUID                        NOT NULL DEFAULT gen_random_uuid(),
    user_id    VARCHAR(255)                NOT NULL,
    amount     NUMERIC(19, 2)              NOT NULL,
    status     VARCHAR(50)                 NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_orders PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders (user_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);
