CREATE TABLE IF NOT EXISTS notifications
(
    id       UUID                        NOT NULL DEFAULT gen_random_uuid(),
    order_id UUID                        NOT NULL,
    type     VARCHAR(50)                 NOT NULL,
    sent_at  TIMESTAMP WITHOUT TIME ZONE,
    status   VARCHAR(50)                 NOT NULL DEFAULT 'PENDING',
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_order_id FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_notifications_order_id ON notifications (order_id);
CREATE INDEX IF NOT EXISTS idx_notifications_status ON notifications (status);
