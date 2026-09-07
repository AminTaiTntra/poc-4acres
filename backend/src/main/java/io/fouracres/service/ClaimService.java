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
