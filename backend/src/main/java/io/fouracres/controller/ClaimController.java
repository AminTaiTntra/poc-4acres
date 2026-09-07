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
