package io.fouracres.repository;

import io.fouracres.model.Patch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PatchRepository extends JpaRepository<Patch, UUID> {}
