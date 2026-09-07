ALTER TABLE patches
  ADD COLUMN status     VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
  ADD COLUMN owner_name VARCHAR(255);

CREATE TABLE claims (
  id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  patch_id      UUID         NOT NULL UNIQUE REFERENCES patches(id) ON DELETE CASCADE,
  steward_name  VARCHAR(255) NOT NULL,
  steward_email VARCHAR(255) NOT NULL,
  claimed_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
