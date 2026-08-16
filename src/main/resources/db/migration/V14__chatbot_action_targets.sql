CREATE TABLE meal_carts (
  meal_cart_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  personalized_routine_id BIGINT,
  partner VARCHAR(100) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  checkout_url VARCHAR(1000),
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_meal_cart_user
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_meal_cart_routine
    FOREIGN KEY (personalized_routine_id)
    REFERENCES personalized_routines(personalized_routine_id) ON DELETE SET NULL,
  CONSTRAINT chk_meal_cart_status
    CHECK (status IN ('ACTIVE', 'ORDERED', 'EXPIRED', 'CANCELLED'))
);

CREATE TABLE meal_cart_items (
  meal_cart_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  meal_cart_id BIGINT NOT NULL,
  market_item_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  unit_price BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_meal_cart_item_cart
    FOREIGN KEY (meal_cart_id) REFERENCES meal_carts(meal_cart_id) ON DELETE CASCADE,
  CONSTRAINT fk_meal_cart_item_market
    FOREIGN KEY (market_item_id) REFERENCES market_items(market_item_id),
  CONSTRAINT uk_meal_cart_market_item UNIQUE (meal_cart_id, market_item_id),
  CONSTRAINT chk_meal_cart_item_quantity CHECK (quantity BETWEEN 1 AND 20),
  CONSTRAINT chk_meal_cart_item_price CHECK (unit_price >= 0)
);

CREATE INDEX idx_meal_carts_user_created
  ON meal_carts(user_id, created_at);

CREATE INDEX idx_meal_cart_items_cart
  ON meal_cart_items(meal_cart_id);
