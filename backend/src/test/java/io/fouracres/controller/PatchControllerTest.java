package io.fouracres.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.BiodiversityData;
import io.fouracres.dto.CarbonData;
import io.fouracres.dto.PatchDto;
import io.fouracres.dto.PatchInsightsDto;
import io.fouracres.dto.SoilData;
import io.fouracres.model.EcosystemType;
import io.fouracres.model.Patch;
import io.fouracres.repository.PatchRepository;
import io.fouracres.service.InsightsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PatchControllerTest {

    private PatchRepository patchRepository;
    private InsightsService insightsService;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        patchRepository = Mockito.mock(PatchRepository.class);
        insightsService = Mockito.mock(InsightsService.class);
        var controller = new PatchController(patchRepository, insightsService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    private Patch buildPatch(UUID id, String name) {
        // Build a simple closed polygon (square)
        Coordinate[] coords = {
            new Coordinate(-62.0, -3.0),
            new Coordinate(-62.0, -3.1),
            new Coordinate(-62.1, -3.1),
            new Coordinate(-62.1, -3.0),
            new Coordinate(-62.0, -3.0)
        };
        LinearRing ring = GF.createLinearRing(coords);
        Polygon polygon = GF.createPolygon(ring, null);

        Patch patch = new Patch();
        patch.setId(id);
        patch.setName(name);
        patch.setEcosystemType(EcosystemType.FOREST);
        patch.setCountry("Brazil");
        patch.setCenterLat(BigDecimal.valueOf(-3.05));
        patch.setCenterLng(BigDecimal.valueOf(-62.05));
        patch.setBoundary(polygon);
        return patch;
    }

    // ── GET /api/patches ──────────────────────────────────────────────────────

    @Test
    void listPatches_returnsEmptyList_whenNoPatches() throws Exception {
        when(patchRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/patches"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listPatches_returnsAllPatches() throws Exception {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        when(patchRepository.findAll()).thenReturn(List.of(
            buildPatch(id1, "Amazon North"),
            buildPatch(id2, "Amazon South")
        ));

        mockMvc.perform(get("/api/patches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("Amazon North"))
            .andExpect(jsonPath("$[1].name").value("Amazon South"))
            .andExpect(jsonPath("$[0].ecosystemType").value("FOREST"))
            .andExpect(jsonPath("$[0].country").value("Brazil"))
            .andExpect(jsonPath("$[0].boundaryGeoJson.type").value("Polygon"));
    }

    // ── GET /api/patches/{id} ─────────────────────────────────────────────────

    @Test
    void getPatch_returns200_whenFound() throws Exception {
        var id = UUID.randomUUID();
        var patch = buildPatch(id, "Test Patch");
        when(patchRepository.findById(id)).thenReturn(Optional.of(patch));

        mockMvc.perform(get("/api/patches/{id}", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.name").value("Test Patch"))
            .andExpect(jsonPath("$.ecosystemType").value("FOREST"))
            .andExpect(jsonPath("$.country").value("Brazil"))
            .andExpect(jsonPath("$.centerLat").value(-3.05))
            .andExpect(jsonPath("$.centerLng").value(-62.05))
            .andExpect(jsonPath("$.boundaryGeoJson.type").value("Polygon"));
    }

    @Test
    void getPatch_returns404_whenNotFound() throws Exception {
        var unknownId = UUID.randomUUID();
        when(patchRepository.findById(unknownId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/patches/{id}", unknownId))
            .andExpect(status().isNotFound());
    }

    // ── GET /api/patches/{id}/insights ────────────────────────────────────────

    @Test
    void getInsights_returns200_withAllSections() throws Exception {
        var id = UUID.randomUUID();
        var insights = new PatchInsightsDto(
            new BiodiversityData(42, List.of(), 3),
            new SoilData(21.5, 5.7, 32.4),
            new CarbonData(35.0, 210.5, 0.08),
            null,
            null,
            null
        );
        when(insightsService.getInsights(id)).thenReturn(insights);

        mockMvc.perform(get("/api/patches/{id}/insights", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.biodiversity.speciesCount").value(42))
            .andExpect(jsonPath("$.biodiversity.threatenedCount").value(3))
            .andExpect(jsonPath("$.soil.ph").value(5.7))
            .andExpect(jsonPath("$.soil.organicCarbonGKg").value(21.5))
            .andExpect(jsonPath("$.carbon.carbonDensityMgHa").value(210.5))
            .andExpect(jsonPath("$.carbon.treeCoverPercent").value(35.0));
    }

    @Test
    void getInsights_returns404_whenPatchNotFound() throws Exception {
        var unknownId = UUID.randomUUID();
        when(insightsService.getInsights(unknownId))
            .thenThrow(new NoSuchElementException("Patch not found: " + unknownId));

        mockMvc.perform(get("/api/patches/{id}/insights", unknownId))
            .andExpect(status().isNotFound());
    }
}
