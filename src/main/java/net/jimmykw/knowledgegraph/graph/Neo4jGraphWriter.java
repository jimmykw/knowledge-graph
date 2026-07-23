package net.jimmykw.knowledgegraph.graph;

import java.time.Instant;
import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;

import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;

import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.collection.Set;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class Neo4jGraphWriter {

    static final String SCHEMA_ID = "default";

    private static final String MERGE_ENTITIES_QUERY = """
            UNWIND $entities AS e
            CALL apoc.merge.node([e.label], {name_norm: e.nameNorm},
                {name: e.name, label: e.label, description: e.description, source_doc: $hash}, {})
            YIELD node
            RETURN e.idx AS idx, id(node) AS id
            """;

    private static final String MERGE_RELATIONSHIPS_QUERY = """
            UNWIND $rels AS r
            MATCH (a), (b) WHERE id(a) = r.srcId AND id(b) = r.tgtId
            CALL apoc.merge.relationship(a, r.type, {description: r.description}, {}, b, {})
            YIELD rel
            RETURN r.idx AS idx, id(rel) AS id
            """;

    private final Driver driver;

    public Option<Long> findDocumentId(String hash) {
        return write(session -> {
            val result = session.run("MATCH (d:Document {hash: $hash}) RETURN id(d) AS id",
                    java.util.Map.of("hash", hash));
            return result.hasNext() ? Option.some(result.next().get("id").asLong()) : Option.none();
        });
    }

    public long mergeDocument(String hash, String filename) {
        val id = write(session -> {
            val record = session.run(
                    "MERGE (d:Document {hash: $hash}) "
                            + "SET d.filename = $filename, d.uploadedAt = $uploadedAt "
                            + "RETURN id(d) AS id",
                    java.util.Map.of("hash", hash,
                            "filename", nullSafe(filename),
                            "uploadedAt", Instant.now().toString()))
                    .single();
            return record.get("id").asLong();
        });
        recordSchema(HashSet.of("Document"), HashSet.empty());
        return id;
    }

    public Map<Integer, Long> mergeEntities(List<ExtractedEntity> entities, String hash) {
        if (entities.isEmpty()) {
            return HashMap.empty();
        }
        return write(session -> {
            val params = java.util.Map.of("entities", entityParams(entities), "hash", hash);
            return List.ofAll(session.run(MERGE_ENTITIES_QUERY, params).list())
                    .foldLeft(HashMap.<Integer, Long>empty(),
                            (acc, row) -> acc.put(row.get("idx").asInt(), row.get("id").asLong()));
        });
    }

    public Map<Integer, Long> mergeRelationships(List<ResolvedRelationship> relationships) {
        if (relationships.isEmpty()) {
            return HashMap.empty();
        }
        return write(session -> List.ofAll(session.run(MERGE_RELATIONSHIPS_QUERY,
                        java.util.Map.of("rels", relationshipParams(relationships))).list())
                .foldLeft(HashMap.<Integer, Long>empty(),
                        (acc, row) -> acc.put(row.get("idx").asInt(), row.get("id").asLong())));
    }

    public void recordSchema(Set<String> labels, Set<String> relTypes) {
        if (labels.isEmpty() && relTypes.isEmpty()) {
            return;
        }
        write(session -> session.run(
                "MERGE (s:Schema {id: $id}) "
                        + "SET s.labels = apoc.coll.union(coalesce(s.labels, []), $labels), "
                        + "    s.relTypes = apoc.coll.union(coalesce(s.relTypes, []), $relTypes), "
                        + "    s.updatedAt = $updatedAt",
                java.util.Map.of("id", SCHEMA_ID,
                        "labels", labels.toJavaList(),
                        "relTypes", relTypes.toJavaList(),
                        "updatedAt", Instant.now().toString())));
    }

    public Option<SchemaSnapshot> readSchema() {
        return write(session -> {
            val result = session.run(
                    "MATCH (s:Schema {id: $id}) RETURN s.labels AS labels, s.relTypes AS relTypes",
                    java.util.Map.of("id", SCHEMA_ID));
            if (!result.hasNext()) {
                return Option.<SchemaSnapshot>none();
            }
            val record = result.next();
            val labels = record.get("labels").isNull()
                    ? java.util.List.<String>of()
                    : record.get("labels").asList(value -> value.asString());
            val relTypes = record.get("relTypes").isNull()
                    ? java.util.List.<String>of()
                    : record.get("relTypes").asList(value -> value.asString());
            return Option.some(new SchemaSnapshot(labels, relTypes));
        });
    }

    private static java.util.List<java.util.Map<String, Object>> entityParams(List<ExtractedEntity> entities) {
        return entities.zipWithIndex()
                .map(pair -> entityParam(pair._1, pair._2))
                .toJavaList();
    }

    private static java.util.Map<String, Object> entityParam(ExtractedEntity entity, int idx) {
        return HashMap.<String, Object>of(
                        "idx", idx,
                        "name", nullSafe(entity.name()),
                        "nameNorm", EntityNormalizer.normalize(entity.name()),
                        "label", entity.label(),
                        "description", nullSafe(entity.description()))
                .toJavaMap();
    }

    private static java.util.List<java.util.Map<String, Object>> relationshipParams(
            List<ResolvedRelationship> relationships) {
        return relationships.zipWithIndex()
                .map(pair -> relationshipParam(pair._1, pair._2))
                .toJavaList();
    }

    private static java.util.Map<String, Object> relationshipParam(ResolvedRelationship rel, int idx) {
        return HashMap.<String, Object>of(
                        "idx", idx,
                        "srcId", rel.sourceId(),
                        "tgtId", rel.targetId(),
                        "type", rel.type(),
                        "description", nullSafe(rel.description()))
                .toJavaMap();
    }

    private <T> T write(Function<Session, T> work) {
        return Try.withResources(() -> driver.session())
                .of(work::apply)
                .getOrElseThrow(this::wrapNeo4jException);
    }

    private RuntimeException wrapNeo4jException(Throwable cause) {
        return switch (cause) {
            case ServiceUnavailableException ignored -> unavailable(cause);
            case TransientException ignored -> unavailable(cause);
            case RuntimeException runtimeException -> runtimeException;
            default -> new RuntimeException(cause);
        };
    }

    private static Neo4jUnavailableException unavailable(Throwable cause) {
        return new Neo4jUnavailableException("Neo4j is unavailable: " + cause.getMessage(), cause);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
