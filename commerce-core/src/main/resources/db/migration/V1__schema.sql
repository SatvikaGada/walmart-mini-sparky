CREATE TABLE products (
                          sku               TEXT PRIMARY KEY,
                          name              TEXT NOT NULL,
                          category          TEXT NOT NULL,
                          description       TEXT NOT NULL,
                          price_paise       INTEGER NOT NULL CHECK (price_paise > 0),
                          tags              TEXT[] NOT NULL DEFAULT '{}',
                          max_qty_per_order INTEGER NOT NULL DEFAULT 10 CHECK (max_qty_per_order > 0),
                          created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_reviews (
                                 id     BIGSERIAL PRIMARY KEY,
                                 sku    TEXT NOT NULL REFERENCES products(sku),
                                 author TEXT NOT NULL,
                                 body   TEXT NOT NULL
);

CREATE TABLE stock (
                       sku         TEXT NOT NULL REFERENCES products(sku),
                       location_id TEXT NOT NULL DEFAULT 'ONLINE',
                       on_hand     INTEGER NOT NULL CHECK (on_hand >= 0),
                       reserved    INTEGER NOT NULL DEFAULT 0 CHECK (reserved >= 0),
                       PRIMARY KEY (sku, location_id),
                       CHECK (reserved <= on_hand)
);

CREATE TABLE carts (
                       id           UUID PRIMARY KEY,
                       session_id   TEXT NOT NULL,
                       status       TEXT NOT NULL CHECK (status IN ('OPEN','CHECKED_OUT','EXPIRED','CANCELLED')),
                       budget_paise INTEGER,
                       created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                       expires_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE cart_items (
                            cart_id          UUID NOT NULL REFERENCES carts(id),
                            sku              TEXT NOT NULL REFERENCES products(sku),
                            location_id      TEXT NOT NULL DEFAULT 'ONLINE',
                            qty              INTEGER NOT NULL CHECK (qty > 0),
                            unit_price_paise INTEGER NOT NULL,
                            PRIMARY KEY (cart_id, sku, location_id)
);

CREATE TABLE orders (
                        id           UUID PRIMARY KEY,
                        cart_id      UUID NOT NULL UNIQUE REFERENCES carts(id),
                        session_id   TEXT NOT NULL,
                        total_paise  INTEGER NOT NULL,
                        status       TEXT NOT NULL DEFAULT 'PLACED',
                        created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
                                  idem_key      TEXT NOT NULL,
                                  scope         TEXT NOT NULL,
                                  request_hash  TEXT NOT NULL,
                                  status        TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','DONE')),
                                  response_json JSONB,
                                  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  PRIMARY KEY (idem_key, scope)
);

CREATE TABLE audit_log (
                           id         BIGSERIAL PRIMARY KEY,
                           ts         TIMESTAMPTZ NOT NULL DEFAULT now(),
                           session_id TEXT,
                           actor      TEXT NOT NULL,
                           action     TEXT NOT NULL,
                           request    JSONB,
                           decision   TEXT NOT NULL CHECK (decision IN ('ALLOWED','BLOCKED')),
                           reason     TEXT
);

CREATE INDEX idx_audit_session_ts ON audit_log (session_id, ts);
CREATE INDEX idx_carts_status_expires ON carts (status, expires_at);