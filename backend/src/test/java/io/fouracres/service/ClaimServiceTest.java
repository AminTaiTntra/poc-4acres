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
