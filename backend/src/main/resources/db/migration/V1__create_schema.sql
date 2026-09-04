-- V1__create_schema.sql
-- Initialize database with PostGIS and core tables
-- Patches: 127m × 127m geospatial units with ecosystem classification
-- Insights_cache: JSONB payload cache for third-party data layers

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE patches (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            VARCHAR(100) NOT NULL,
  ecosystem_type  VARCHAR(50)  NOT NULL,
  country         VARCHAR(100) NOT NULL,
  description     TEXT,
  center_lat      DECIMAL(10,7) NOT NULL,
  center_lng      DECIMAL(10,7) NOT NULL,
  boundary        GEOMETRY(Polygon, 4326) NOT NULL,
  area_acres      DECIMAL(5,2) NOT NULL DEFAULT 4.0,
  gfw_geostore_id VARCHAR(100),
  created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_patches_boundary ON patches USING GIST(boundary);

CREATE TABLE insights_cache (
  patch_id    UUID        NOT NULL REFERENCES patches(id) ON DELETE CASCADE,
  layer       VARCHAR(20) NOT NULL,
  payload     JSONB       NOT NULL,
  fetched_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMP   NOT NULL,
  PRIMARY KEY (patch_id, layer)
);
