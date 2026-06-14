CREATE TABLE user_module_access (
  id VARCHAR(36) PRIMARY KEY,
  factory_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64) NOT NULL,
  module_code VARCHAR(100) NOT NULL,
  access_type VARCHAR(8) NOT NULL CHECK (access_type IN ('GRANT','DENY')),
  granted_by VARCHAR(64),
  remark VARCHAR(500),
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP,
  CONSTRAINT uk_uma UNIQUE (factory_id, user_id, module_code)
);

CREATE INDEX idx_uma_factory_user ON user_module_access(factory_id, user_id);
