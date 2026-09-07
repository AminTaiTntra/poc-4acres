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
