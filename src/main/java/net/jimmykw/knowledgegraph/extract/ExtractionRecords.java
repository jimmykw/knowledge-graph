package net.jimmykw.knowledgegraph.extract;

import java.util.List;

public final class ExtractionRecords {

    private ExtractionRecords() {
    }

    public record ExtractionResult(
            List<ExtractedEntity> entities,
            List<ExtractedRelationship> relationships) {
    }

    public record ExtractedEntity(String name, String label, String description) {
    }

    public record ExtractedRelationship(
            String sourceName,
            String sourceLabel,
            String targetName,
            String targetLabel,
            String type,
            String description) {
    }
}
