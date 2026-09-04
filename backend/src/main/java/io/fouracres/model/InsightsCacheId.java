package io.fouracres.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class InsightsCacheId implements Serializable {
    private UUID patchId;
    private String layer;

    public InsightsCacheId() {}
    public InsightsCacheId(UUID patchId, String layer) {
        this.patchId = patchId;
        this.layer = layer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InsightsCacheId that)) return false;
        return Objects.equals(patchId, that.patchId) && Objects.equals(layer, that.layer);
    }

    @Override
    public int hashCode() { return Objects.hash(patchId, layer); }
}
