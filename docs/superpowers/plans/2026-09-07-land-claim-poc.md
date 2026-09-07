# Land Claim POC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add land registration (draw polygon on map), steward claiming, and an immersive 3D digital twin page to the existing geospatial POC.

**Architecture:** Three new Next.js routes (`/register`, `/explore` updated, `/patch/[id]`), two new Spring Boot endpoints (`POST /api/patches`, `POST /api/patches/{id}/claim`), one new Flyway migration (V3 adds `status`/`owner_name` to patches, creates `claims` table). No auth. The steward's `/patch/[id]` URL is their only access key.

**Tech Stack:** Java 21 · Spring Boot 3.3.4 · PostGIS 3.4 · Next.js 14 App Router · TypeScript strict · Tailwind CSS v4 · `@mapbox/mapbox-gl-draw` v1.x · `@tanstack/react-query` v5 · Docker Compose (unchanged)

**Spec:** `docs/superpowers/specs/2026-09-07-land-claim-poc-design.md`

## Global Constraints

- Java 21, Spring Boot 3.3.4, PostgreSQL 15 + PostGIS 3.4
- Next.js 14 App Router (`app/` directory), TypeScript strict, Tailwind CSS v4
- `@mapbox/mapbox-gl-draw` v1.4.3 exact (peer dep on mapbox-gl v3)
- Flyway 10.x — `flyway-database-postgresql` already in pom.xml; do NOT add it again
- No authentication, no sessions, no passwords anywhere
- One steward per patch — enforced by `UNIQUE` constraint on `claims.patch_id`
- All API clients (GBIF, SoilGrids, GFW) and `InsightsService` are unchanged
- `PatchDto` is a **Java record** — adding fields requires updating the canonical record declaration and its `from()` factory; do NOT convert it to a class
- Working directory: `backend/` for Java; `frontend/` for Next.js
- All tests run without a live database (Mockito only); do NOT start Docker

---

## Task 1: DB Migration + Backend Models

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__add_claim_support.sql`
- Create: `backend/src/main/java/io/fouracres/model/PatchStatus.java`
- Create: `backend/src/main/java/io/fouracres/model/Claim.java`
- Create: `backend/src/main/java/io/fouracres/repository/ClaimRepository.java`
- Modify: `backend/src/main/java/io/fouracres/model/Patch.java` (add `status` + `ownerName` fields)

**Interfaces:**
- Produces: `PatchStatus` enum (`AVAILABLE`, `CLAIMED`); `Patch.getStatus()` defaulting to `AVAILABLE`; `Patch.getOwnerName()`; `Claim` entity; `ClaimRepository.findByPatch_Id(UUID)`

---

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V3__add_claim_support.sql`:

```sql
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
```

- [ ] **Step 2: Write `PatchStatus.java`**

Create `backend/src/main/java/io/fouracres/model/PatchStatus.java`:

```java
package io.fouracres.model;

public enum PatchStatus {
    AVAILABLE,
    CLAIMED
}
```

- [ ] **Step 3: Add `status` and `ownerName` to `Patch.java`**

Open `backend/src/main/java/io/fouracres/model/Patch.java`. After the `gfwGeostoreId` field (line ~38), add:

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PatchStatus status = PatchStatus.AVAILABLE;

    @Column(name = "owner_name", length = 255)
    private String ownerName;
```

Add getters and setters alongside the existing ones:

```java
    public PatchStatus getStatus() { return status; }
    public String getOwnerName() { return ownerName; }
    public void setStatus(PatchStatus status) { this.status = status; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
```

Add the import at the top of the file:

```java
import io.fouracres.model.PatchStatus;
```

- [ ] **Step 4: Write `Claim.java`**

Create `backend/src/main/java/io/fouracres/model/Claim.java`:

```java
package io.fouracres.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "claims")
public class Claim {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne
    @JoinColumn(name = "patch_id", nullable = false, unique = true)
    private Patch patch;

    @Column(name = "steward_name", nullable = false)
    private String stewardName;

    @Column(name = "steward_email", nullable = false)
    private String stewardEmail;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt = Instant.now();

    public UUID getId() { return id; }
    public Patch getPatch() { return patch; }
    public String getStewardName() { return stewardName; }
    public String getStewardEmail() { return stewardEmail; }
    public Instant getClaimedAt() { return claimedAt; }

    public void setId(UUID id) { this.id = id; }
    public void setPatch(Patch patch) { this.patch = patch; }
    public void setStewardName(String stewardName) { this.stewardName = stewardName; }
    public void setStewardEmail(String stewardEmail) { this.stewardEmail = stewardEmail; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
}
```

- [ ] **Step 5: Write `ClaimRepository.java`**

Create `backend/src/main/java/io/fouracres/repository/ClaimRepository.java`:

```java
package io.fouracres.repository;

import io.fouracres.model.Claim;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    Optional<Claim> findByPatch_Id(UUID patchId);
}
```

- [ ] **Step 6: Run existing tests to confirm no regressions**

```bash
cd backend && mvn test -q
```

Expected: **12 tests, 0 failures**. The `buildPatch()` helper in `PatchControllerTest` does not set `status`, so the field defaults to `PatchStatus.AVAILABLE` — no change to existing test behaviour.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__add_claim_support.sql \
        backend/src/main/java/io/fouracres/model/PatchStatus.java \
        backend/src/main/java/io/fouracres/model/Claim.java \
        backend/src/main/java/io/fouracres/repository/ClaimRepository.java \
        backend/src/main/java/io/fouracres/model/Patch.java
git commit -m "feat: V3 migration, PatchStatus enum, Claim entity, ClaimRepository"
```

---

## Task 2: DTOs + PatchDto Record Update

**Files:**
- Create: `backend/src/main/java/io/fouracres/dto/GeoJsonPolygon.java`
- Create: `backend/src/main/java/io/fouracres/dto/PatchRegistrationRequest.java`
- Create: `backend/src/main/java/io/fouracres/dto/ClaimRequest.java`
- Create: `backend/src/main/java/io/fouracres/dto/ClaimDto.java`
- Modify: `backend/src/main/java/io/fouracres/dto/PatchDto.java` (add `status`, `ownerName` to record)

**Interfaces:**
- Consumes: `PatchStatus`, `Claim` from Task 1
- Produces: `PatchRegistrationRequest` (name, description, ownerName, country, ecosystemType, boundary); `ClaimRequest` (stewardName, stewardEmail); `ClaimDto` (id, patchId, stewardName, stewardEmail, claimedAt); `PatchDto` with `status` + `ownerName` fields; `ClaimDto.from(Claim)`

---

- [ ] **Step 1: Write `GeoJsonPolygon.java`**

Create `backend/src/main/java/io/fouracres/dto/GeoJsonPolygon.java`:

```java
package io.fouracres.dto;

import java.util.List;

public class GeoJsonPolygon {
    private String type;
    private List<List<List<Double>>> coordinates;

    public String getType() { return type; }
    public List<List<List<Double>>> getCoordinates() { return coordinates; }
    public void setType(String type) { this.type = type; }
    public void setCoordinates(List<List<List<Double>>> coordinates) { this.coordinates = coordinates; }
}
```

- [ ] **Step 2: Write `PatchRegistrationRequest.java`**

Create `backend/src/main/java/io/fouracres/dto/PatchRegistrationRequest.java`:

```java
package io.fouracres.dto;

public class PatchRegistrationRequest {
    private String name;
    private String description;
    private String ownerName;
    private String country;
    private String ecosystemType;
    private GeoJsonPolygon boundary;

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getOwnerName() { return ownerName; }
    public String getCountry() { return country; }
    public String getEcosystemType() { return ecosystemType; }
    public GeoJsonPolygon getBoundary() { return boundary; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public void setCountry(String country) { this.country = country; }
    public void setEcosystemType(String ecosystemType) { this.ecosystemType = ecosystemType; }
    public void setBoundary(GeoJsonPolygon boundary) { this.boundary = boundary; }
}
```

- [ ] **Step 3: Write `ClaimRequest.java`**

Create `backend/src/main/java/io/fouracres/dto/ClaimRequest.java`:

```java
package io.fouracres.dto;

public class ClaimRequest {
    private String stewardName;
    private String stewardEmail;

    public String getStewardName() { return stewardName; }
    public String getStewardEmail() { return stewardEmail; }
    public void setStewardName(String stewardName) { this.stewardName = stewardName; }
    public void setStewardEmail(String stewardEmail) { this.stewardEmail = stewardEmail; }
}
```

- [ ] **Step 4: Write `ClaimDto.java`**

Create `backend/src/main/java/io/fouracres/dto/ClaimDto.java`:

```java
package io.fouracres.dto;

import io.fouracres.model.Claim;
import java.time.Instant;
import java.util.UUID;

public record ClaimDto(
    UUID id,
    UUID patchId,
    String stewardName,
    String stewardEmail,
    Instant claimedAt
) {
    public static ClaimDto from(Claim c) {
        return new ClaimDto(
            c.getId(),
            c.getPatch().getId(),
            c.getStewardName(),
            c.getStewardEmail(),
            c.getClaimedAt()
        );
    }
}
```

- [ ] **Step 5: Update `PatchDto.java` record — add `status` and `ownerName`**

Replace the entire content of `backend/src/main/java/io/fouracres/dto/PatchDto.java` with:

```java
package io.fouracres.dto;

import io.fouracres.model.Patch;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record PatchDto(
    UUID id,
    String name,
    String ecosystemType,
    String country,
    double centerLat,
    double centerLng,
    Object boundaryGeoJson,
    String status,
    String ownerName
) {
    public static PatchDto from(Patch p) {
        var coords = Arrays.stream(p.getBoundary().getCoordinates())
            .map(c -> List.of(c.x, c.y))
            .toList();
        var geojson = java.util.Map.of(
            "type", "Polygon",
            "coordinates", List.of(coords)
        );
        return new PatchDto(
            p.getId(),
            p.getName(),
            p.getEcosystemType().name(),
            p.getCountry(),
            p.getCenterLat().doubleValue(),
            p.getCenterLng().doubleValue(),
            geojson,
            p.getStatus().name(),
            p.getOwnerName()
        );
    }
}
```

- [ ] **Step 6: Run all backend tests**

```bash
cd backend && mvn test -q
```

Expected: **12 tests, 0 failures**. The `PatchControllerTest` assertions check specific JSON fields (`name`, `ecosystemType`, `country`, `boundaryGeoJson.type`) — the new `status` and `ownerName` fields appear in the response JSON but are not asserted, so all existing tests still pass.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/io/fouracres/dto/
git commit -m "feat: DTOs for registration and claim, update PatchDto with status/ownerName"
```

---

## Task 3: ClaimService + Tests

**Files:**
- Create: `backend/src/main/java/io/fouracres/service/ClaimService.java`
- Create: `backend/src/test/java/io/fouracres/service/ClaimServiceTest.java`

**Interfaces:**
- Consumes: `PatchRepository`, `ClaimRepository`, `Patch`, `PatchStatus`, `Claim`, `PatchRegistrationRequest`, `ClaimRequest`, `PatchDto`, `ClaimDto`, `GeoJsonPolygon`, `EcosystemType`
- Produces: `ClaimService.registerPatch(PatchRegistrationRequest): PatchDto`; `ClaimService.claimPatch(UUID, ClaimRequest): ClaimDto`; `ClaimService.getClaim(UUID): Optional<ClaimDto>`

---

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/io/fouracres/service/ClaimServiceTest.java`:

```java
package io.fouracres.service;

import io.fouracres.dto.*;
import io.fouracres.model.Claim;
import io.fouracres.model.EcosystemType;
import io.fouracres.model.Patch;
import io.fouracres.model.PatchStatus;
import io.fouracres.repository.ClaimRepository;
import io.fouracres.repository.PatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClaimServiceTest {

    private PatchRepository patchRepo;
    private ClaimRepository claimRepo;
    private ClaimService service;

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        patchRepo = Mockito.mock(PatchRepository.class);
        claimRepo = Mockito.mock(ClaimRepository.class);
        service = new ClaimService(patchRepo, claimRepo);
    }

    private PatchRegistrationRequest buildRegRequest() {
        var geo = new GeoJsonPolygon();
        geo.setType("Polygon");
        geo.setCoordinates(List.of(List.of(
            List.of(-3.89, 57.12),
            List.of(-3.88, 57.12),
            List.of(-3.88, 57.13),
            List.of(-3.89, 57.13),
            List.of(-3.89, 57.12)
        )));
        var req = new PatchRegistrationRequest();
        req.setName("Sunlit Meadow");
        req.setOwnerName("Jane Smith");
        req.setCountry("Scotland");
        req.setEcosystemType("HIGHLAND");
        req.setBoundary(geo);
        return req;
    }

    @Test
    void registerPatch_createsPatchWithAvailableStatus() {
        var req = buildRegRequest();
        when(patchRepo.save(any())).thenAnswer(inv -> {
            Patch p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        PatchDto result = service.registerPatch(req);

        assertThat(result.name()).isEqualTo("Sunlit Meadow");
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.ownerName()).isEqualTo("Jane Smith");
        assertThat(result.country()).isEqualTo("Scotland");
        verify(patchRepo).save(any());
    }

    @Test
    void claimPatch_success_returnsClaimDtoAndMarksClaimed() {
        var patchId = UUID.randomUUID();
        var patch = new Patch();
        patch.setId(patchId);
        patch.setName("Test Patch");
        patch.setEcosystemType(EcosystemType.FOREST);
        patch.setCountry("Brazil");
        patch.setCenterLat(BigDecimal.valueOf(-3.05));
        patch.setCenterLng(BigDecimal.valueOf(-62.05));
        Coordinate[] coords = {
            new Coordinate(-62.0, -3.0), new Coordinate(-62.0, -3.1),
            new Coordinate(-62.1, -3.1), new Coordinate(-62.1, -3.0),
            new Coordinate(-62.0, -3.0)
        };
        patch.setBoundary(GF.createPolygon(coords));
        patch.setStatus(PatchStatus.AVAILABLE);

        when(patchRepo.findById(patchId)).thenReturn(Optional.of(patch));
        when(claimRepo.save(any())).thenAnswer(inv -> {
            Claim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(patchRepo.save(any())).thenReturn(patch);

        var req = new ClaimRequest();
        req.setStewardName("Alice");
        req.setStewardEmail("alice@example.com");

        ClaimDto result = service.claimPatch(patchId, req);

        assertThat(result.stewardName()).isEqualTo("Alice");
        assertThat(result.stewardEmail()).isEqualTo("alice@example.com");
        assertThat(result.patchId()).isEqualTo(patchId);
        assertThat(patch.getStatus()).isEqualTo(PatchStatus.CLAIMED);
        verify(claimRepo).save(any());
        verify(patchRepo).save(patch);
    }

    @Test
    void claimPatch_alreadyClaimed_throws409() {
        var patchId = UUID.randomUUID();
        var patch = new Patch();
        patch.setId(patchId);
        patch.setStatus(PatchStatus.CLAIMED);
        when(patchRepo.findById(patchId)).thenReturn(Optional.of(patch));

        var req = new ClaimRequest();
        req.setStewardName("Bob");
        req.setStewardEmail("bob@example.com");

        assertThatThrownBy(() -> service.claimPatch(patchId, req))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("already been claimed");

        verifyNoInteractions(claimRepo);
    }

    @Test
    void claimPatch_notFound_throws404() {
        var unknownId = UUID.randomUUID();
        when(patchRepo.findById(unknownId)).thenReturn(Optional.empty());

        var req = new ClaimRequest();
        req.setStewardName("Charlie");
        req.setStewardEmail("charlie@example.com");

        assertThatThrownBy(() -> service.claimPatch(unknownId, req))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd backend && mvn test -pl . -Dtest=ClaimServiceTest -q 2>&1 | tail -5
```

Expected: compilation failure — `ClaimService` does not exist yet.

- [ ] **Step 3: Write `ClaimService.java`**

Create `backend/src/main/java/io/fouracres/service/ClaimService.java`:

```java
package io.fouracres.service;

import io.fouracres.dto.*;
import io.fouracres.model.*;
import io.fouracres.repository.ClaimRepository;
import io.fouracres.repository.PatchRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ClaimService {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private final PatchRepository patchRepository;
    private final ClaimRepository claimRepository;

    public ClaimService(PatchRepository patchRepository, ClaimRepository claimRepository) {
        this.patchRepository = patchRepository;
        this.claimRepository = claimRepository;
    }

    @Transactional
    public PatchDto registerPatch(PatchRegistrationRequest req) {
        List<List<Double>> ring = req.getBoundary().getCoordinates().get(0);
        Coordinate[] coords = ring.stream()
            .map(c -> new Coordinate(c.get(0), c.get(1)))
            .toArray(Coordinate[]::new);
        Polygon polygon = GF.createPolygon(coords);

        Patch patch = new Patch();
        patch.setName(req.getName());
        patch.setDescription(req.getDescription());
        patch.setOwnerName(req.getOwnerName());
        patch.setCountry(req.getCountry());
        patch.setEcosystemType(EcosystemType.valueOf(req.getEcosystemType()));
        patch.setBoundary(polygon);
        patch.setCenterLng(BigDecimal.valueOf(polygon.getCentroid().getX()));
        patch.setCenterLat(BigDecimal.valueOf(polygon.getCentroid().getY()));
        patch.setAreaAcres(BigDecimal.valueOf(4.0));
        patch.setStatus(PatchStatus.AVAILABLE);

        return PatchDto.from(patchRepository.save(patch));
    }

    @Transactional
    public ClaimDto claimPatch(UUID patchId, ClaimRequest req) {
        Patch patch = patchRepository.findById(patchId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patch not found"));
        if (patch.getStatus() != PatchStatus.AVAILABLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This patch has already been claimed");
        }

        Claim claim = new Claim();
        claim.setPatch(patch);
        claim.setStewardName(req.getStewardName());
        claim.setStewardEmail(req.getStewardEmail());
        claimRepository.save(claim);

        patch.setStatus(PatchStatus.CLAIMED);
        patchRepository.save(patch);

        return ClaimDto.from(claim);
    }

    public Optional<ClaimDto> getClaim(UUID patchId) {
        return claimRepository.findByPatch_Id(patchId).map(ClaimDto::from);
    }
}
```

- [ ] **Step 4: Run all backend tests**

```bash
cd backend && mvn test -q
```

Expected: **16 tests, 0 failures** (12 existing + 4 new ClaimServiceTest).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/service/ClaimService.java \
        backend/src/test/java/io/fouracres/service/ClaimServiceTest.java
git commit -m "feat: ClaimService — registerPatch, claimPatch, getClaim with tests"
```

---

## Task 4: ClaimController + Test

**Files:**
- Create: `backend/src/main/java/io/fouracres/controller/ClaimController.java`
- Create: `backend/src/test/java/io/fouracres/controller/ClaimControllerTest.java`

**Interfaces:**
- Consumes: `ClaimService` from Task 3
- Produces: `POST /api/patches` → 201 PatchDto; `POST /api/patches/{id}/claim` → 201 ClaimDto; `GET /api/patches/{id}/claim` → 200 ClaimDto or 404

---

- [ ] **Step 1: Write the failing controller tests**

Create `backend/src/test/java/io/fouracres/controller/ClaimControllerTest.java`:

```java
package io.fouracres.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.*;
import io.fouracres.service.ClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClaimControllerTest {

    private ClaimService claimService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        claimService = Mockito.mock(ClaimService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ClaimController(claimService)).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void registerPatch_returns201_withPatchDto() throws Exception {
        var patchId = UUID.randomUUID();
        var dto = new PatchDto(patchId, "Sunlit Meadow", "HIGHLAND", "Scotland",
            57.125, -3.89, Map.of("type", "Polygon", "coordinates", List.of()), "AVAILABLE", "Jane Smith");

        when(claimService.registerPatch(any())).thenReturn(dto);

        var body = Map.of(
            "name", "Sunlit Meadow",
            "ownerName", "Jane Smith",
            "country", "Scotland",
            "ecosystemType", "HIGHLAND",
            "boundary", Map.of("type", "Polygon", "coordinates",
                List.of(List.of(
                    List.of(-3.89, 57.12), List.of(-3.88, 57.12),
                    List.of(-3.88, 57.13), List.of(-3.89, 57.13),
                    List.of(-3.89, 57.12)
                )))
        );

        mockMvc.perform(post("/api/patches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Sunlit Meadow"))
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.ownerName").value("Jane Smith"));
    }

    @Test
    void claimPatch_returns201_withClaimDto() throws Exception {
        var patchId = UUID.randomUUID();
        var claimId = UUID.randomUUID();
        var dto = new ClaimDto(claimId, patchId, "Alice", "alice@example.com", Instant.now());

        when(claimService.claimPatch(eq(patchId), any())).thenReturn(dto);

        var body = Map.of("stewardName", "Alice", "stewardEmail", "alice@example.com");

        mockMvc.perform(post("/api/patches/{id}/claim", patchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.stewardName").value("Alice"))
            .andExpect(jsonPath("$.patchId").value(patchId.toString()));
    }

    @Test
    void claimPatch_returns409_whenAlreadyClaimed() throws Exception {
        var patchId = UUID.randomUUID();
        when(claimService.claimPatch(eq(patchId), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "This patch has already been claimed"));

        var body = Map.of("stewardName", "Bob", "stewardEmail", "bob@example.com");

        mockMvc.perform(post("/api/patches/{id}/claim", patchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isConflict());
    }

    @Test
    void getClaim_returns200_whenExists() throws Exception {
        var patchId = UUID.randomUUID();
        var claimId = UUID.randomUUID();
        var dto = new ClaimDto(claimId, patchId, "Alice", "alice@example.com", Instant.now());
        when(claimService.getClaim(patchId)).thenReturn(Optional.of(dto));

        mockMvc.perform(get("/api/patches/{id}/claim", patchId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stewardName").value("Alice"));
    }

    @Test
    void getClaim_returns404_whenNoClaim() throws Exception {
        var patchId = UUID.randomUUID();
        when(claimService.getClaim(patchId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/patches/{id}/claim", patchId))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd backend && mvn test -pl . -Dtest=ClaimControllerTest -q 2>&1 | tail -5
```

Expected: compilation failure — `ClaimController` does not exist yet.

- [ ] **Step 3: Write `ClaimController.java`**

Create `backend/src/main/java/io/fouracres/controller/ClaimController.java`:

```java
package io.fouracres.controller;

import io.fouracres.dto.ClaimDto;
import io.fouracres.dto.ClaimRequest;
import io.fouracres.dto.PatchDto;
import io.fouracres.dto.PatchRegistrationRequest;
import io.fouracres.service.ClaimService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/patches")
public class ClaimController {

    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    @PostMapping
    public ResponseEntity<PatchDto> registerPatch(@RequestBody PatchRegistrationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claimService.registerPatch(req));
    }

    @PostMapping("/{id}/claim")
    public ResponseEntity<ClaimDto> claimPatch(
            @PathVariable UUID id,
            @RequestBody ClaimRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(claimService.claimPatch(id, req));
    }

    @GetMapping("/{id}/claim")
    public ResponseEntity<ClaimDto> getClaim(@PathVariable UUID id) {
        return claimService.getClaim(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 4: Run all backend tests**

```bash
cd backend && mvn test -q
```

Expected: **21 tests, 0 failures** (16 existing + 5 new ClaimControllerTest).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/controller/ClaimController.java \
        backend/src/test/java/io/fouracres/controller/ClaimControllerTest.java
git commit -m "feat: ClaimController — POST /api/patches, POST/GET /api/patches/{id}/claim"
```

---

## Task 5: Frontend Foundation

**Files:**
- Modify: `frontend/package.json` (add `@mapbox/mapbox-gl-draw`)
- Modify: `frontend/app/lib/types.ts` (add `PatchStatus`, `ClaimDto`, `GeoJsonPolygon`, update `Patch`)
- Modify: `frontend/app/lib/api.ts` (add `fetchPatch`, `registerPatch`, `claimPatch`, `fetchClaim`)
- Modify: `frontend/app/lib/queries.ts` (add `usePatch`, `useRegisterPatch`, `useClaimPatch`, `useClaim`)

**Interfaces:**
- Produces: `Patch.status: PatchStatus`; `Patch.ownerName: string | null`; `ClaimDto`; `useRegisterPatch()`; `useClaimPatch()`; `usePatch(id)`; `useClaim(id)`

---

- [ ] **Step 1: Install `@mapbox/mapbox-gl-draw`**

```bash
cd frontend && npm install @mapbox/mapbox-gl-draw@^1.4.3
npm install --save-dev @types/mapbox__mapbox-gl-draw@^1.4.3
```

- [ ] **Step 2: Update `frontend/app/lib/types.ts`**

Replace the entire file with:

```typescript
export interface SpeciesEntry {
  name: string
  kingdom: string
}

export interface BiodiversityData {
  speciesCount: number
  topSpecies: SpeciesEntry[]
  threatenedCount: number
}

export interface SoilData {
  organicCarbonGKg: number
  ph: number
  clayPercent: number
}

export interface CarbonData {
  treeCoverPercent: number
  carbonDensityMgHa: number
  coverLossHa: number
}

export interface PatchInsights {
  biodiversity: BiodiversityData
  soil: SoilData
  carbon: CarbonData
}

export interface BoundaryGeoJson {
  type: 'Polygon'
  coordinates: number[][][]
}

export type PatchStatus = 'AVAILABLE' | 'CLAIMED'

export interface Patch {
  id: string
  name: string
  ecosystemType: string
  country: string
  centerLat: number
  centerLng: number
  boundaryGeoJson: BoundaryGeoJson
  status: PatchStatus
  ownerName: string | null
}

export interface ClaimDto {
  id: string
  patchId: string
  stewardName: string
  stewardEmail: string
  claimedAt: string
}

export interface GeoJsonPolygon {
  type: 'Polygon'
  coordinates: number[][][]
}

export interface PatchRegistrationRequest {
  name: string
  description?: string
  ownerName: string
  country: string
  ecosystemType: string
  boundary: GeoJsonPolygon
}

export interface ClaimRequest {
  stewardName: string
  stewardEmail: string
}
```

- [ ] **Step 3: Update `frontend/app/lib/api.ts`**

Replace the entire file with:

```typescript
import { ClaimDto, ClaimRequest, Patch, PatchInsights, PatchRegistrationRequest } from './types'

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

export async function fetchPatches(): Promise<Patch[]> {
  const res = await fetch(`${BASE}/api/patches`)
  if (!res.ok) throw new Error(`fetchPatches failed: ${res.status}`)
  return res.json()
}

export async function fetchPatch(patchId: string): Promise<Patch> {
  const res = await fetch(`${BASE}/api/patches/${patchId}`)
  if (!res.ok) throw new Error(`fetchPatch failed: ${res.status}`)
  return res.json()
}

export async function fetchInsights(patchId: string): Promise<PatchInsights> {
  const res = await fetch(`${BASE}/api/patches/${patchId}/insights`)
  if (!res.ok) throw new Error(`fetchInsights failed: ${res.status}`)
  return res.json()
}

export async function fetchClaim(patchId: string): Promise<ClaimDto> {
  const res = await fetch(`${BASE}/api/patches/${patchId}/claim`)
  if (!res.ok) throw new Error(`fetchClaim failed: ${res.status}`)
  return res.json()
}

export async function registerPatch(req: PatchRegistrationRequest): Promise<Patch> {
  const res = await fetch(`${BASE}/api/patches`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (!res.ok) throw new Error(`registerPatch failed: ${res.status}`)
  return res.json()
}

export async function claimPatch(patchId: string, req: ClaimRequest): Promise<ClaimDto> {
  const res = await fetch(`${BASE}/api/patches/${patchId}/claim`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (res.status === 409) throw new Error('This patch has already been claimed')
  if (!res.ok) throw new Error(`claimPatch failed: ${res.status}`)
  return res.json()
}
```

- [ ] **Step 4: Update `frontend/app/lib/queries.ts`**

Replace the entire file with:

```typescript
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  claimPatch, fetchClaim, fetchInsights, fetchPatch, fetchPatches, registerPatch,
} from './api'
import type { ClaimRequest, PatchRegistrationRequest } from './types'

export function usePatches() {
  return useQuery({
    queryKey: ['patches'],
    queryFn: fetchPatches,
    staleTime: Infinity,
  })
}

export function usePatch(patchId: string | null) {
  return useQuery({
    queryKey: ['patch', patchId],
    queryFn: () => fetchPatch(patchId!),
    enabled: patchId != null,
    staleTime: Infinity,
  })
}

export function useInsights(patchId: string | null) {
  return useQuery({
    queryKey: ['insights', patchId],
    queryFn: () => fetchInsights(patchId!),
    enabled: patchId != null,
    staleTime: 1000 * 60 * 60 * 24,
  })
}

export function useClaim(patchId: string | null) {
  return useQuery({
    queryKey: ['claim', patchId],
    queryFn: () => fetchClaim(patchId!),
    enabled: patchId != null,
    retry: false,
  })
}

export function useRegisterPatch() {
  return useMutation({
    mutationFn: (req: PatchRegistrationRequest) => registerPatch(req),
  })
}

export function useClaimPatch() {
  return useMutation({
    mutationFn: ({ patchId, ...req }: { patchId: string } & ClaimRequest) =>
      claimPatch(patchId, req),
  })
}
```

- [ ] **Step 5: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit
```

Expected: 0 errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/package.json frontend/package-lock.json \
        frontend/app/lib/types.ts frontend/app/lib/api.ts frontend/app/lib/queries.ts
git commit -m "feat: install mapbox-gl-draw, extend frontend types/api/queries for claim flow"
```

---

## Task 6: DrawMap Component + Register Page

**Files:**
- Create: `frontend/app/components/DrawMap.tsx`
- Create: `frontend/app/register/page.tsx`

**Interfaces:**
- Consumes: `useRegisterPatch()` from Task 5; `NEXT_PUBLIC_MAPBOX_TOKEN` env var
- Produces: route `/register` — full-screen satellite map with polygon draw tool + form panel

---

- [ ] **Step 1: Write `DrawMap.tsx`**

Create `frontend/app/components/DrawMap.tsx`:

```tsx
'use client'

import Map from 'react-map-gl'
import MapboxDraw from '@mapbox/mapbox-gl-draw'
import '@mapbox/mapbox-gl-draw/dist/mapbox-gl-draw.css'
import { useRef } from 'react'
import type { MapRef } from 'react-map-gl'

interface Props {
  onPolygonDrawn: (coords: number[][] | null) => void
}

export function DrawMap({ onPolygonDrawn }: Props) {
  const mapRef = useRef<MapRef>(null)
  const drawRef = useRef<MapboxDraw | null>(null)

  function handleLoad() {
    const map = mapRef.current?.getMap()
    if (!map) return

    const draw = new MapboxDraw({
      displayControlsDefault: false,
      controls: { polygon: true, trash: true },
    })
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    map.addControl(draw as any)
    drawRef.current = draw

    map.on('draw.create', updatePolygon)
    map.on('draw.update', updatePolygon)
    map.on('draw.delete', () => onPolygonDrawn(null))
  }

  function updatePolygon() {
    const features = drawRef.current?.getAll().features ?? []
    if (features.length > 0) {
      const coords = (features[0].geometry as GeoJSON.Polygon).coordinates[0] as number[][]
      onPolygonDrawn(coords)
    }
  }

  return (
    <Map
      ref={mapRef}
      mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
      initialViewState={{ longitude: 0, latitude: 20, zoom: 2 }}
      style={{ width: '100%', height: '100%' }}
      mapStyle="mapbox://styles/mapbox/satellite-v9"
      onLoad={handleLoad}
    />
  )
}
```

- [ ] **Step 2: Write the register page**

Create `frontend/app/register/page.tsx`:

```tsx
'use client'

import dynamic from 'next/dynamic'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useRegisterPatch } from '../lib/queries'

const DrawMap = dynamic(
  () => import('../components/DrawMap').then(m => ({ default: m.DrawMap })),
  { ssr: false }
)

const ECOSYSTEMS = ['FOREST', 'WETLAND', 'SAVANNA', 'HIGHLAND', 'DRYLAND', 'COASTAL']

export default function RegisterPage() {
  const router = useRouter()
  const [coords, setCoords] = useState<number[][] | null>(null)
  const [form, setForm] = useState({
    name: '', ownerName: '', country: '', ecosystemType: 'FOREST', description: '',
  })
  const [error, setError] = useState('')
  const { mutate: register, isPending } = useRegisterPatch()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!coords) { setError('Please draw your land boundary first'); return }
    setError('')
    register(
      { ...form, boundary: { type: 'Polygon', coordinates: [coords] } },
      {
        onSuccess: () => router.push('/explore'),
        onError: (err) => setError(err instanceof Error ? err.message : 'Registration failed'),
      }
    )
  }

  return (
    <div className="h-screen flex overflow-hidden bg-[#0a1628]">
      {/* Form panel */}
      <div className="w-96 flex-shrink-0 flex flex-col bg-slate-900/90 backdrop-blur border-r border-slate-700 overflow-y-auto">
        <div className="p-6 border-b border-slate-700">
          <a href="/" className="text-emerald-400 text-sm hover:text-emerald-300 transition-colors">
            ← 4Acres Earth
          </a>
          <h1 className="text-xl font-bold text-white mt-3">Register Your Land</h1>
          <p className="text-slate-400 text-sm mt-1">
            Draw your 4-acre boundary on the satellite map, then complete the details.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4 flex-1 flex flex-col">
          {[
            { label: 'Land Name', key: 'name', placeholder: 'e.g. Sunlit Meadow' },
            { label: 'Your Name', key: 'ownerName', placeholder: 'Land owner name' },
            { label: 'Country', key: 'country', placeholder: 'e.g. Scotland' },
          ].map(({ label, key, placeholder }) => (
            <div key={key}>
              <label className="block text-xs font-medium text-slate-400 mb-1">{label} *</label>
              <input
                required
                value={form[key as keyof typeof form]}
                onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
                className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
                placeholder={placeholder}
              />
            </div>
          ))}

          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Ecosystem Type *</label>
            <select
              value={form.ecosystemType}
              onChange={e => setForm(f => ({ ...f, ecosystemType: e.target.value }))}
              className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
            >
              {ECOSYSTEMS.map(eco => <option key={eco} value={eco}>{eco}</option>)}
            </select>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Description</label>
            <textarea
              value={form.description}
              onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              rows={3}
              className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500 resize-none"
              placeholder="Describe the land..."
            />
          </div>

          <div className="flex-1" />

          {coords && (
            <p className="text-emerald-400 text-sm flex items-center gap-1.5">
              <span>✓</span> Boundary drawn ({coords.length - 1} points)
            </p>
          )}

          {error && <p className="text-red-400 text-sm">{error}</p>}

          <button
            type="submit"
            disabled={isPending}
            className="w-full bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-medium py-2.5 rounded-lg transition-colors text-sm"
          >
            {isPending ? 'Registering...' : 'Register Land →'}
          </button>
        </form>
      </div>

      {/* Map */}
      <div className="flex-1 relative">
        {!coords && (
          <div className="absolute top-4 left-1/2 -translate-x-1/2 z-10 bg-black/70 backdrop-blur-sm text-white text-sm px-4 py-2 rounded-full pointer-events-none">
            Use the polygon tool (top-right of map) to draw your land boundary
          </div>
        )}
        <DrawMap onPolygonDrawn={setCoords} />
      </div>
    </div>
  )
}
```

- [ ] **Step 3: Verify TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Expected: 0 errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/app/components/DrawMap.tsx frontend/app/register/
git commit -m "feat: DrawMap component and /register land registration page"
```

---

## Task 7: ClaimModal + Updated Explore Page

**Files:**
- Create: `frontend/app/components/ClaimModal.tsx`
- Modify: `frontend/app/components/PatchSidebar.tsx` (add status badge, claim button, View Digital Twin link)
- Modify: `frontend/app/components/PatchMap.tsx` (color patches by status)
- Modify: `frontend/app/explore/page.tsx` (wire ClaimModal state)

**Interfaces:**
- Consumes: `useClaimPatch()`, `Patch.status`, `Patch.ownerName` from Task 5
- Produces: amber/green patch colors on map; claim modal; "View Digital Twin →" link

---

- [ ] **Step 1: Write `ClaimModal.tsx`**

Create `frontend/app/components/ClaimModal.tsx`:

```tsx
'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useClaimPatch } from '../lib/queries'
import type { Patch } from '../lib/types'

interface Props {
  patch: Patch
  onClose: () => void
}

export function ClaimModal({ patch, onClose }: Props) {
  const router = useRouter()
  const [form, setForm] = useState({ stewardName: '', stewardEmail: '' })
  const [error, setError] = useState('')
  const { mutate: claim, isPending } = useClaimPatch()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    claim(
      { patchId: patch.id, ...form },
      {
        onSuccess: () => router.push(`/patch/${patch.id}`),
        onError: (err) => setError(err instanceof Error ? err.message : 'Claim failed'),
      }
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="bg-slate-900 border border-slate-700 rounded-2xl p-6 w-full max-w-md mx-4 shadow-2xl">
        <div className="flex items-start justify-between mb-4">
          <div>
            <h2 className="text-lg font-bold text-white">Claim Your 4 Acres</h2>
            <p className="text-slate-400 text-sm mt-0.5">{patch.name}</p>
          </div>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-white text-2xl leading-none ml-4"
          >
            ×
          </button>
        </div>

        <p className="text-slate-300 text-sm mb-5">
          Enter your details to become the steward of this patch.
          You will receive a unique URL to view your land.
        </p>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Your Name *</label>
            <input
              required
              value={form.stewardName}
              onChange={e => setForm(f => ({ ...f, stewardName: e.target.value }))}
              className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
              placeholder="Full name"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Email *</label>
            <input
              required
              type="email"
              value={form.stewardEmail}
              onChange={e => setForm(f => ({ ...f, stewardEmail: e.target.value }))}
              className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
              placeholder="you@example.com"
            />
          </div>

          {error && <p className="text-red-400 text-sm">{error}</p>}

          <div className="flex gap-3 pt-1">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 bg-slate-700 hover:bg-slate-600 text-white font-medium py-2.5 rounded-lg transition-colors text-sm"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isPending}
              className="flex-1 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-medium py-2.5 rounded-lg transition-colors text-sm"
            >
              {isPending ? 'Claiming...' : 'Claim This Patch'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Update `PatchSidebar.tsx`**

Replace the entire content of `frontend/app/components/PatchSidebar.tsx` with:

```tsx
'use client'

import { useState } from 'react'
import type { Patch } from '../lib/types'

const ECOSYSTEMS = ['ALL', 'FOREST', 'WETLAND', 'SAVANNA', 'HIGHLAND', 'DRYLAND', 'COASTAL']

const ECOSYSTEM_COLORS: Record<string, string> = {
  FOREST: 'bg-emerald-600',
  WETLAND: 'bg-blue-600',
  SAVANNA: 'bg-amber-600',
  HIGHLAND: 'bg-purple-600',
  DRYLAND: 'bg-orange-600',
  COASTAL: 'bg-cyan-600',
}

interface Props {
  patches: Patch[]
  selectedPatchId: string | null
  onSelect: (id: string) => void
  onClaim: (patch: Patch) => void
}

export function PatchSidebar({ patches, selectedPatchId, onSelect, onClaim }: Props) {
  const [filter, setFilter] = useState('ALL')

  const visible = filter === 'ALL' ? patches : patches.filter(p => p.ecosystemType === filter)
  const selectedPatch = patches.find(p => p.id === selectedPatchId)

  return (
    <aside className="w-72 flex-shrink-0 bg-slate-900/90 backdrop-blur border-r border-slate-700
                      flex flex-col h-full overflow-hidden">
      <div className="p-4 border-b border-slate-700">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-slate-300 uppercase tracking-wider">Ecosystem</h2>
          <a
            href="/register"
            className="text-xs text-emerald-400 hover:text-emerald-300 transition-colors"
          >
            + Register Land
          </a>
        </div>
        <div className="flex flex-wrap gap-1">
          {ECOSYSTEMS.map(eco => (
            <button
              key={eco}
              onClick={() => setFilter(eco)}
              className={`px-2 py-1 rounded text-xs font-medium transition-colors ${
                filter === eco
                  ? 'bg-emerald-500 text-white'
                  : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
              }`}
            >
              {eco}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {visible.map(patch => (
          <button
            key={patch.id}
            onClick={() => onSelect(patch.id)}
            className={`w-full text-left p-3 rounded-lg transition-all ${
              patch.id === selectedPatchId
                ? 'bg-emerald-900/60 ring-1 ring-emerald-500'
                : 'bg-slate-800 hover:bg-slate-700'
            }`}
          >
            <div className="flex items-center gap-2 mb-1">
              <span className={`w-2 h-2 rounded-full flex-shrink-0 ${
                ECOSYSTEM_COLORS[patch.ecosystemType] ?? 'bg-slate-500'
              }`} />
              <span className="text-sm font-medium text-white truncate flex-1">{patch.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded font-medium flex-shrink-0 ${
                patch.status === 'AVAILABLE'
                  ? 'bg-amber-500/20 text-amber-300'
                  : 'bg-emerald-500/20 text-emerald-300'
              }`}>
                {patch.status === 'AVAILABLE' ? 'Open' : 'Claimed'}
              </span>
            </div>
            <div className="text-xs text-slate-400 ml-4">
              {patch.country} · {patch.ecosystemType}
            </div>
            {patch.ownerName && (
              <div className="text-xs text-slate-500 ml-4 mt-0.5">Owner: {patch.ownerName}</div>
            )}
          </button>
        ))}
        {visible.length === 0 && (
          <p className="text-slate-500 text-sm text-center py-8">No patches in this ecosystem</p>
        )}
      </div>

      {selectedPatch && (
        <div className="p-3 border-t border-slate-700">
          {selectedPatch.status === 'AVAILABLE' ? (
            <button
              onClick={() => onClaim(selectedPatch)}
              className="w-full bg-emerald-600 hover:bg-emerald-500 text-white font-medium py-2.5 rounded-lg text-sm transition-colors"
            >
              Claim This Patch
            </button>
          ) : (
            <a
              href={`/patch/${selectedPatch.id}`}
              className="block w-full text-center bg-slate-700 hover:bg-slate-600 text-white font-medium py-2.5 rounded-lg text-sm transition-colors"
            >
              View Digital Twin →
            </a>
          )}
        </div>
      )}
    </aside>
  )
}
```

- [ ] **Step 3: Update `PatchMap.tsx` — color patches by status**

Open `frontend/app/components/PatchMap.tsx`. In the `geojson` FeatureCollection (line ~18), add `status` to each feature's properties:

```tsx
  const geojson: FeatureCollection<Polygon> = {
    type: 'FeatureCollection',
    features: patches.map(p => ({
      type: 'Feature',
      id: p.id,
      properties: { id: p.id, name: p.name, selected: p.id === selectedPatchId, status: p.status },
      geometry: p.boundaryGeoJson as Polygon,
    })),
  }
```

Replace the `patch-fill` layer paint (currently lines ~54–66) with:

```tsx
        <Layer
          id="patch-fill"
          type="fill"
          paint={{
            'fill-color': [
              'case',
              ['==', ['get', 'selected'], true], '#10b981',
              ['==', ['get', 'status'], 'AVAILABLE'], '#f59e0b',
              '#10b981',
            ],
            'fill-opacity': [
              'case',
              ['==', ['get', 'selected'], true], 0.5,
              0.3,
            ],
          }}
        />
```

- [ ] **Step 4: Update `explore/page.tsx` — add ClaimModal state**

Replace the entire content of `frontend/app/explore/page.tsx` with:

```tsx
'use client'

import { useState } from 'react'
import dynamic from 'next/dynamic'
import { usePatches } from '../lib/queries'
import { PatchSidebar } from '../components/PatchSidebar'
import { InsightsDrawer } from '../components/InsightsDrawer'
import { ClaimModal } from '../components/ClaimModal'
import type { Patch } from '../lib/types'

const PatchMap = dynamic(
  () => import('../components/PatchMap').then(m => ({ default: m.PatchMap })),
  { ssr: false }
)

export default function ExplorePage() {
  const [selectedPatchId, setSelectedPatchId] = useState<string | null>(null)
  const [claimTarget, setClaimTarget] = useState<Patch | null>(null)
  const { data: patches = [], isLoading } = usePatches()

  const selectedPatch = patches.find(p => p.id === selectedPatchId)

  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-slate-400 animate-pulse">Loading patches...</div>
      </div>
    )
  }

  return (
    <div className="h-screen flex overflow-hidden">
      <PatchSidebar
        patches={patches}
        selectedPatchId={selectedPatchId}
        onSelect={setSelectedPatchId}
        onClaim={setClaimTarget}
      />
      <div className="flex-1 relative">
        <PatchMap
          patches={patches}
          selectedPatchId={selectedPatchId}
          onPatchSelect={setSelectedPatchId}
        />
      </div>
      <InsightsDrawer
        patchId={selectedPatchId}
        patchName={selectedPatch?.name ?? ''}
      />
      {claimTarget && (
        <ClaimModal
          patch={claimTarget}
          onClose={() => setClaimTarget(null)}
        />
      )}
    </div>
  )
}
```

- [ ] **Step 5: Verify TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Expected: 0 errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/app/components/ClaimModal.tsx \
        frontend/app/components/PatchSidebar.tsx \
        frontend/app/components/PatchMap.tsx \
        frontend/app/explore/page.tsx
git commit -m "feat: ClaimModal, status-coloured PatchMap, updated sidebar with claim/twin buttons"
```

---

## Task 8: My Patch Digital Twin

**Files:**
- Create: `frontend/app/components/MyPatchMap.tsx`
- Create: `frontend/app/components/PatchInfoCard.tsx`
- Create: `frontend/app/patch/[id]/page.tsx`

**Interfaces:**
- Consumes: `usePatch(id)`, `useInsights(id)`, `useClaim(id)` from Task 5; `Patch`, `PatchInsights`, `ClaimDto` types
- Produces: route `/patch/[id]` — full-screen immersive satellite view with 3D terrain, fly-in camera, floating metric cards

---

- [ ] **Step 1: Write `PatchInfoCard.tsx`**

Create `frontend/app/components/PatchInfoCard.tsx`:

```tsx
import type { ClaimDto, Patch, PatchInsights } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

function Skeleton() {
  return <div className="h-4 bg-white/10 rounded animate-pulse" />
}

interface IdentityProps {
  type: 'identity'
  patch: Patch
  claim: ClaimDto | null | undefined
  className?: string
}
interface MetricProps {
  type: 'biodiversity' | 'soil' | 'carbon'
  insights: PatchInsights | null | undefined
  className?: string
}
type Props = IdentityProps | MetricProps

export function PatchInfoCard(props: Props) {
  if (props.type === 'identity') {
    const { patch, claim } = props
    return (
      <div className={`${GLASS} ${props.className ?? ''}`}>
        <div className="flex items-start justify-between gap-2 mb-2">
          <h1 className="text-base font-bold leading-tight">{patch.name}</h1>
          <span className={`text-xs px-2 py-0.5 rounded-full font-medium flex-shrink-0 ${
            patch.status === 'CLAIMED' ? 'bg-emerald-500/30 text-emerald-300' : 'bg-amber-500/30 text-amber-300'
          }`}>
            {patch.status === 'CLAIMED' ? 'Claimed' : 'Available'}
          </span>
        </div>
        <p className="text-slate-400 text-xs mb-3">{patch.ecosystemType} · {patch.country}</p>
        {patch.ownerName && (
          <p className="text-slate-300 text-xs mb-1">
            <span className="text-slate-500">Land owner</span> {patch.ownerName}
          </p>
        )}
        {claim ? (
          <p className="text-slate-300 text-xs">
            <span className="text-slate-500">Steward</span> {claim.stewardName}
          </p>
        ) : (
          <p className="text-slate-500 text-xs">Unclaimed — visit /explore to claim</p>
        )}
      </div>
    )
  }

  if (props.type === 'biodiversity') {
    const bio = props.insights?.biodiversity
    return (
      <div className={`${GLASS} ${props.className ?? ''}`}>
        <p className="text-xs font-semibold text-emerald-400 mb-2">🌿 Biodiversity</p>
        {bio ? (
          <>
            <p className="text-2xl font-bold">{bio.speciesCount}</p>
            <p className="text-slate-400 text-xs mb-1">species recorded</p>
            <p className="text-slate-300 text-xs">{bio.threatenedCount} threatened</p>
            {bio.topSpecies[0] && (
              <p className="text-slate-500 text-xs mt-1 truncate">{bio.topSpecies[0].name}</p>
            )}
          </>
        ) : <Skeleton />}
      </div>
    )
  }

  if (props.type === 'soil') {
    const soil = props.insights?.soil
    return (
      <div className={`${GLASS} ${props.className ?? ''}`}>
        <p className="text-xs font-semibold text-amber-400 mb-2">🌱 Soil Health</p>
        {soil ? (
          <div className="grid grid-cols-3 gap-2 text-center">
            <div>
              <p className="text-lg font-bold">{soil.organicCarbonGKg.toFixed(1)}</p>
              <p className="text-slate-400 text-xs">g/kg C</p>
            </div>
            <div>
              <p className="text-lg font-bold">{soil.ph.toFixed(1)}</p>
              <p className="text-slate-400 text-xs">pH</p>
            </div>
            <div>
              <p className="text-lg font-bold">{soil.clayPercent.toFixed(0)}%</p>
              <p className="text-slate-400 text-xs">clay</p>
            </div>
          </div>
        ) : <Skeleton />}
      </div>
    )
  }

  // carbon
  const carbon = props.insights?.carbon
  return (
    <div className={`${GLASS} ${props.className ?? ''}`}>
      <p className="text-xs font-semibold text-blue-400 mb-2">🌲 Carbon / Forest</p>
      {carbon ? (
        <div className="grid grid-cols-3 gap-2 text-center">
          <div>
            <p className="text-lg font-bold">{carbon.treeCoverPercent.toFixed(1)}%</p>
            <p className="text-slate-400 text-xs">tree cover</p>
          </div>
          <div>
            <p className="text-lg font-bold">{carbon.carbonDensityMgHa.toFixed(1)}</p>
            <p className="text-slate-400 text-xs">Mg/ha C</p>
          </div>
          <div>
            <p className="text-lg font-bold">{carbon.coverLossHa.toFixed(2)}</p>
            <p className="text-slate-400 text-xs">ha lost</p>
          </div>
        </div>
      ) : <Skeleton />}
    </div>
  )
}
```

- [ ] **Step 2: Write `MyPatchMap.tsx`**

Create `frontend/app/components/MyPatchMap.tsx`:

```tsx
'use client'

import Map from 'react-map-gl'
import { useRef } from 'react'
import type { MapRef } from 'react-map-gl'
import type { Patch } from '../lib/types'

interface Props {
  patch: Patch
}

export function MyPatchMap({ patch }: Props) {
  const mapRef = useRef<MapRef>(null)

  function handleLoad() {
    const map = mapRef.current?.getMap()
    if (!map) return

    map.addSource('mapbox-dem', {
      type: 'raster-dem',
      url: 'mapbox://mapbox.mapbox-terrain-v2',
      tileSize: 512,
    })

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(map as any).setTerrain({ source: 'mapbox-dem', exaggeration: 1.5 })

    map.addLayer({
      id: 'sky',
      type: 'sky',
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      paint: { 'sky-type': 'atmosphere', 'sky-atmosphere-sun': [0.0, 90.0], 'sky-atmosphere-sun-intensity': 15 } as any,
    })

    map.addSource('patch-boundary', {
      type: 'geojson',
      data: { type: 'Feature', geometry: patch.boundaryGeoJson, properties: {} },
    })

    map.addLayer({
      id: 'patch-fill',
      type: 'fill',
      source: 'patch-boundary',
      paint: { 'fill-color': '#10b981', 'fill-opacity': 0.25 },
    })

    map.addLayer({
      id: 'patch-outline',
      type: 'line',
      source: 'patch-boundary',
      paint: { 'line-color': '#10b981', 'line-width': 3, 'line-blur': 1 },
    })

    map.flyTo({
      center: [patch.centerLng, patch.centerLat],
      zoom: 15.5,
      pitch: 60,
      bearing: -20,
      duration: 3500,
      essential: true,
    })
  }

  return (
    <Map
      ref={mapRef}
      mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
      initialViewState={{
        longitude: patch.centerLng,
        latitude: patch.centerLat,
        zoom: 5,
      }}
      style={{ width: '100%', height: '100%' }}
      mapStyle="mapbox://styles/mapbox/satellite-v9"
      onLoad={handleLoad}
    />
  )
}
```

- [ ] **Step 3: Write `/patch/[id]/page.tsx`**

Create `frontend/app/patch/[id]/page.tsx`:

```tsx
'use client'

import dynamic from 'next/dynamic'
import { useParams } from 'next/navigation'
import { usePatch, useInsights, useClaim } from '../../lib/queries'
import { PatchInfoCard } from '../../components/PatchInfoCard'

const MyPatchMap = dynamic(
  () => import('../../components/MyPatchMap').then(m => ({ default: m.MyPatchMap })),
  { ssr: false }
)

export default function PatchPage() {
  const { id } = useParams<{ id: string }>()
  const { data: patch, isLoading } = usePatch(id)
  const { data: insights } = useInsights(id)
  const { data: claim } = useClaim(id)

  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-slate-400 animate-pulse">Loading your patch...</div>
      </div>
    )
  }

  if (!patch) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-center">
          <p className="text-slate-400 mb-4">Patch not found</p>
          <a href="/explore" className="text-emerald-400 hover:text-emerald-300 text-sm transition-colors">
            ← Back to explore
          </a>
        </div>
      </div>
    )
  }

  return (
    <div className="relative h-screen overflow-hidden">
      <MyPatchMap patch={patch} />

      {/* Top-right nav */}
      <div className="absolute top-4 right-4 z-10">
        <a
          href="/explore"
          className="text-white/70 hover:text-white text-sm bg-black/40 backdrop-blur-sm px-3 py-1.5 rounded-full transition-colors"
        >
          ← 4Acres Earth
        </a>
      </div>

      {/* Identity card — top-left */}
      <div className="absolute top-4 left-4 z-10 w-64">
        <PatchInfoCard type="identity" patch={patch} claim={claim} />
      </div>

      {/* Metrics strip — bottom */}
      <div className="absolute bottom-6 left-4 right-4 z-10 flex gap-3">
        <PatchInfoCard type="biodiversity" insights={insights} className="flex-1" />
        <PatchInfoCard type="soil" insights={insights} className="flex-1" />
        <PatchInfoCard type="carbon" insights={insights} className="flex-1" />
      </div>
    </div>
  )
}
```

- [ ] **Step 4: Verify TypeScript**

```bash
cd frontend && npx tsc --noEmit
```

Expected: 0 errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/components/MyPatchMap.tsx \
        frontend/app/components/PatchInfoCard.tsx \
        frontend/app/patch/
git commit -m "feat: My Patch digital twin — 3D terrain, fly-in camera, glassmorphism insight cards"
```

---

## Post-build verification

After all tasks complete, rebuild the full Docker stack to verify end-to-end:

```bash
# From repo root
docker compose up --build
```

Verify the following flows manually:

1. **Globe** — `http://localhost:3000` — rotating globe with patch pins visible
2. **Register** — `http://localhost:3000/register` — draw a polygon, fill form, submit → redirects to `/explore`; new patch appears in sidebar as amber "Open"
3. **Explore** — click the new patch → "Claim This Patch" button appears at the bottom of sidebar; click it → ClaimModal opens
4. **Claim** — enter name + email → redirects to `/patch/[id]`
5. **Digital Twin** — `/patch/[id]` — satellite map animates into the patch at 60° pitch; three metric cards appear at the bottom; identity card shows steward name
6. **Status update** — return to `/explore`; the claimed patch now shows green "Claimed" and "View Digital Twin →" link; map fills it green
