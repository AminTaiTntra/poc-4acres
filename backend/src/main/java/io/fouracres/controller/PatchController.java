package io.fouracres.controller;

import io.fouracres.dto.PatchDto;
import io.fouracres.dto.PatchInsightsDto;
import io.fouracres.repository.PatchRepository;
import io.fouracres.service.InsightsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/patches")
public class PatchController {
    private final PatchRepository patchRepository;
    private final InsightsService insightsService;

    public PatchController(PatchRepository patchRepository, InsightsService insightsService) {
        this.patchRepository = patchRepository;
        this.insightsService = insightsService;
    }

    @GetMapping
    public List<PatchDto> listPatches() {
        return patchRepository.findAll().stream().map(PatchDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PatchDto> getPatch(@PathVariable UUID id) {
        return patchRepository.findById(id)
            .map(p -> ResponseEntity.ok(PatchDto.from(p)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/insights")
    public ResponseEntity<PatchInsightsDto> getInsights(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(insightsService.getInsights(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
