package net.jimmykw.knowledgegraph.testsupport;

import io.vavr.collection.List;
import lombok.experimental.UtilityClass;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedRelationship;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;
import tools.jackson.databind.json.JsonMapper;

/** Builds {@link ExtractionResult}s and the JSON an LLM would return for them. */
@UtilityClass
public class ExtractionJson {

    private static final JsonMapper JSON = JsonMapper.shared();

    public static ExtractedEntity entity(String name, String label) {
        return new ExtractedEntity(name, label, name + " description");
    }

    public static ExtractedRelationship relationship(String source, String sourceLabel, String type, String target, String targetLabel) {
        return new ExtractedRelationship(source, sourceLabel, target, targetLabel, type, type + " description");
    }

    public static ExtractionResult entities(ExtractedEntity... entities) {
        return new ExtractionResult(java.util.List.of(entities), java.util.List.of());
    }

    public static ExtractionResult relationships(ExtractedRelationship... relationships) {
        return new ExtractionResult(java.util.List.of(), java.util.List.of(relationships));
    }

    public static String json(ExtractionResult result) {
        return JSON.writeValueAsString(result);
    }

    public static List<ExtractedEntity> entityList(ExtractedEntity... entities) {
        return List.of(entities);
    }
}
