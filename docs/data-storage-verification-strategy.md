# Data Storage & Verification Strategy

**Scope:** Real product architecture — what to store, how much, and how evidence is calculated  
**Status:** Design thinking — not yet implemented

---

## The core question

The POC fetches live data on demand and caches it for 24 hours. In the real product, we need to answer a harder question: **can we prove that a specific piece of land had a specific environmental state at a specific point in time, and that this state was reached legitimately?**

This matters for:
- Carbon credit claims ("this land sequestered X tonnes between date A and date B")
- Land ownership verification ("this boundary has not changed since registration")
- Ecosystem restoration claims ("vegetation improved from fair → good over 3 years")
- Dispute resolution ("the owner claims degradation started after a neighbouring event")

---

## What NOT to store

| Data | Why not |
|---|---|
| Raw satellite imagery | Terabytes per parcel; GEE stores this for us — no reason to duplicate |
| Raw API response JSON verbatim | Too large, changes format; store computed metrics instead |
| Weather history beyond 30 days | Regenerate on demand from Open-Meteo; not evidence-grade |
| GBIF species lists in full | The GBIF API is stable and queryable — store counts + hashes, not full lists |
| Pixel-level NDVI grids | GEE computes these; we only need the reduced statistics |

**Rule of thumb:** store the *result* of computation, not the inputs. The inputs are held by the authoritative source (GEE, GBIF, SoilGrids). Store enough to reproduce and verify the result.

---

## Storage tiers

```
Tier 1 — Hot cache (current)
  └─ insights_cache table, 24h TTL
  └─ Purpose: performance, avoid re-hitting APIs on every page load
  └─ OK to lose and regenerate

Tier 2 — Time-series history (new)
  └─ Annual metric snapshots per patch
  └─ Purpose: show trends, power the sparkline charts
  └─ Immutable once written (append-only)
  └─ Retained: forever

Tier 3 — Evidence records (new)
  └─ Signed, tamper-evident verification snapshots
  └─ Purpose: support claims, disputes, third-party verification
  └─ Append-only, never updated
  └─ Retained: forever + archived off-DB after 7 years

Tier 4 — Audit log (new)
  └─ Every computation: inputs, outputs, source version, timestamp
  └─ Purpose: reproducibility — anyone can verify we computed correctly
  └─ Retained: 7 years minimum (regulatory standard for environmental claims)
```

---

## How much data?

### Scale estimates (1 million parcels, 10 years)

| Table | Rows | Row size | Total |
|---|---|---|---|
| `patch_metrics_history` (annual NDVI etc) | 5M | ~1 KB | ~5 GB |
| `verifications` (baseline + annual) | 12M | ~3 KB | ~36 GB |
| `audit_log` | 50M | ~2 KB | ~100 GB |
| `insights_cache` (hot, 24h) | 1M | ~5 KB | ~5 GB |

**Total: ~150 GB** — well within a single managed PostgreSQL instance (e.g. AWS RDS db.r6g.2xlarge) up to ~10M parcels.

The data profile is primarily **write-once, read-many** — ideal for PostgreSQL with proper indexing and table partitioning by year.

---

## Proposed schema additions

```sql
-- Annual metric snapshots — one row per patch per metric per year
-- Immutable: never UPDATE, only INSERT
CREATE TABLE patch_metrics_history (
  id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  patch_id        UUID        NOT NULL REFERENCES patches(id),
  metric_type     TEXT        NOT NULL,  -- 'VEGETATION', 'CARBON', 'BIODIVERSITY', 'SOIL'
  year            INT         NOT NULL,
  season_window   TEXT,                  -- e.g. '10-11' (Oct–Nov)
  recorded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  data            JSONB       NOT NULL,  -- computed metrics for that year
  source          TEXT        NOT NULL,  -- 'Sentinel-2', 'GBIF', etc.
  source_version  TEXT,                  -- GEE collection version, API version
  computation_ver TEXT        NOT NULL,  -- our algorithm version e.g. '1.0.0'
  UNIQUE (patch_id, metric_type, year)
);

-- Verification / evidence records — triggered events, not periodic snapshots
-- Append-only. Never update a row — dispute by adding a new row with type='DISPUTE'
CREATE TABLE verifications (
  id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  patch_id        UUID        NOT NULL REFERENCES patches(id),
  type            TEXT        NOT NULL,  -- 'BASELINE','ANNUAL','CLAIM_SUPPORT','DISPUTE','THIRD_PARTY'
  triggered_by    TEXT        NOT NULL,  -- 'SYSTEM', 'OWNER', 'VERIFIER', 'REGULATOR'
  triggered_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  valid_at        TIMESTAMPTZ NOT NULL,  -- the point in time the evidence describes
  evidence        JSONB       NOT NULL,  -- full evidence payload (see below)
  evidence_hash   TEXT        NOT NULL,  -- SHA-256 of evidence JSON, canonical form
  signature       TEXT,                  -- HMAC or asymmetric signature of evidence_hash
  status          TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING, VERIFIED, DISPUTED, EXPIRED
  notes           TEXT
);

-- Full audit of every computation — inputs + outputs
CREATE TABLE computation_audit (
  id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  patch_id        UUID        NOT NULL REFERENCES patches(id),
  computed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  service         TEXT        NOT NULL,  -- 'GEE', 'GBIF', 'SOILGRIDS', 'GFW'
  request_params  JSONB       NOT NULL,  -- exact parameters sent to the service
  response_hash   TEXT        NOT NULL,  -- SHA-256 of the raw response body
  computed_result JSONB       NOT NULL,  -- what our code derived from the response
  algorithm_ver   TEXT        NOT NULL,
  duration_ms     INT
);
```

---

## What an evidence record looks like

```json
{
  "evidence_id": "550e8400-e29b-41d4-a716-446655440000",
  "patch_id": "123e4567-e89b-12d3-a456-426614174000",
  "type": "ANNUAL",
  "valid_at": "2024-10-15T00:00:00Z",
  "metrics": {
    "ndvi": {
      "current": 0.7465,
      "baseline": 0.5154,
      "change_pct": 44.84,
      "trend": "improving",
      "condition": "good",
      "yearly": [
        { "year": 2022, "ndvi": 0.2209 },
        { "year": 2023, "ndvi": 0.5053 },
        { "year": 2024, "ndvi": 0.8200 },
        { "year": 2025, "ndvi": 0.7465 }
      ]
    },
    "carbon": { "tree_cover_pct": 65, "carbon_density_mg_ha": 42.3 },
    "biodiversity": { "species_count": 148, "threatened_count": 12 }
  },
  "sources": [
    {
      "service": "GEE",
      "collection": "COPERNICUS/S2_SR_HARMONIZED",
      "date_range": "2024-10-01/2024-11-30",
      "cloud_filter": "QA60 bits 10+11",
      "scale_m": 10
    },
    {
      "service": "GBIF",
      "endpoint": "/occurrence/search",
      "response_hash": "sha256:a3f1..."
    },
    {
      "service": "GFW",
      "geostore_id": "abc123",
      "response_hash": "sha256:b9e2..."
    }
  ],
  "algorithm_version": "1.2.0",
  "produced_by": "4acres-api",
  "produced_at": "2025-09-10T14:32:00Z"
}
```

The `evidence_hash` is `SHA-256(canonical_json(evidence))` — any tampering changes the hash.  
The `signature` signs that hash with a server-side private key — proving 4 Acres produced this record.

---

## How verification is calculated

### Three verification levels

**Level 1 — Self-certified (POC / MVP)**
- Our system computes the metrics, stores a signed snapshot
- We vouch that the data came from GEE/GBIF/SoilGrids on the stated date
- Trusted by: the land owner, internal users
- Suitable for: internal dashboards, land owner reports
- Cost: free

**Level 2 — Independently reproducible (production)**
- We store the exact API parameters and response hashes
- A third party can re-run the same GEE computation with the same parameters and verify the result matches
- Trusted by: auditors, NGOs, ESG platforms
- Suitable for: carbon credit pre-verification, ESG reporting
- Cost: low (just store more audit data)

**Level 3 — Third-party verified (regulated)**
- An accredited verifier (e.g. Verra, Gold Standard, SustainCERT) runs their own satellite analysis and signs off
- We store their verification record alongside ours
- Trusted by: carbon markets, regulators, institutional investors
- Suitable for: selling verified carbon credits, meeting compliance standards
- Cost: varies ($500–$5,000 per parcel depending on standard)

---

## When to trigger evidence snapshots

| Trigger | Type | Who pays / initiates |
|---|---|---|
| First land registration | `BASELINE` | System (automatic) |
| Annual schedule (every Oct–Nov) | `ANNUAL` | System (cron job) |
| Owner makes a claim or lists for carbon | `CLAIM_SUPPORT` | Owner-initiated |
| Dispute raised by a third party | `DISPUTE` | Third party or regulator |
| Third-party audit requested | `THIRD_PARTY` | Owner pays for accreditation |

---

## The verification flow (production)

```
1. Trigger fired (registration, annual cron, owner request)
         ↓
2. Fetch current metrics from all sources
   (GEE → NDVI, GBIF → biodiversity, GFW → carbon, SoilGrids → soil)
         ↓
3. Store raw response hashes in computation_audit
         ↓
4. Compute evidence payload (JSON with all metrics + sources + algorithm version)
         ↓
5. Hash the payload (SHA-256, canonical JSON)
         ↓
6. Sign the hash with our server private key
         ↓
7. Store in verifications table (append-only)
         ↓
8. Store annual values in patch_metrics_history (for trend charting)
         ↓
9. Notify owner: "Your land verification for 2024 is ready"
```

---

## What stays off the blockchain (and what doesn't)

**Don't put on a blockchain:**
- Raw metrics, detailed evidence payloads — too expensive, too much data
- Anything that changes or is corrected

**Could put on a blockchain (anchoring only):**
- The `evidence_hash` of each annual verification — proves the record existed at that time and hasn't changed
- This is called **blockchain anchoring** — low cost (~$0.01 per record on Polygon/Ethereum L2), high trust
- A hash on-chain + the full record in our DB = trustless verification without migrating our stack

**When to add blockchain anchoring:**
- When entering regulated carbon markets (Verra, Gold Standard require audit trails)
- When institutional investors need trustless verification
- Not for MVP — PostgreSQL with signed records is sufficient until then

---

## Summary recommendations

| Decision | Recommendation |
|---|---|
| Store raw satellite data? | No — GEE/Copernicus holds it |
| Store computed metrics? | Yes — annual snapshots, append-only |
| Cache TTL? | 24h for performance (current); trigger evidence snapshot annually |
| Evidence format? | Signed JSON with source hashes — reproducible by any third party |
| Verification level for MVP? | Level 1 (self-certified) with Level 2 audit data captured from day one |
| Blockchain? | Not for MVP — add anchoring when entering carbon markets |
| Retention | Evidence records: forever. Audit log: 7 years minimum |
| Biggest risk | Not capturing audit data from day one — hard to backfill |
