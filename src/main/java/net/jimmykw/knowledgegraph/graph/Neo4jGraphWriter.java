package net.jimmykw.knowledgegraph.graph;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;

import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;

import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class Neo4jGraphWriter {

    static final String SCHEMA_ID = "default";

    private final Driver driver;

    public Option<Long> findDocumentId(String hash) {
        return write(session -> {
            val result = session.run("MATCH (d:Document {hash: $hash}) RETURN id(d) AS id", Map.of("hash", hash));
            return result.hasNext() ? Option.some(result.next().get("id").asLong()) : Option.none();
        });
    }

    public long mergeDocument(String hash, String filename) {
        val id = write(session -> {
            val record = session.run(
                    "MERGE (d:Document {hash: $hash}) "
                            + "SET d.filename = $filename, d.uploadedAt = $uploadedAt "
                            + "RETURN id(d) AS id",
                    Map.of("hash", hash,
                            "filename", nullSafe(filename),
                            "uploadedAt", Instant.now().toString()))
                    .single();
            return record.get("id").asLong();
        });
        recordSchemaLabel("Document");
        return id;
    }

    public long mergeEntity(String name, String nameNorm, String label, String description, String hash) {
        val id = write(session -> {
            val record = session.run(
                    "CALL apoc.merge.node("
                            + "[$label], "
                            + "{name_norm: $nameNorm}, "
                            + "{name: $name, label: $label, description: $description, source_doc: $hash}, "
                            + "{}) "
                            + "YIELD node RETURN id(node) AS id",
                    Map.of("label", label,
                            "nameNorm", nameNorm,
                            "name", nullSafe(name),
                            "description", nullSafe(description),
                            "hash", hash))
                    .single();
            return record.get("id").asLong();
        });
        recordSchemaLabel(label);
        return id;
    }

    public long mergeRelationship(long srcId, long tgtId, String type, String description) {
        val id = write(session -> {
            val record = session.run(
                    "MATCH (a), (b) WHERE id(a) = $srcId AND id(b) = $tgtId "
                            + "CALL apoc.merge.relationship(a, $type, {description: $description}, {}, b, {}) YIELD rel "
                            + "RETURN id(rel) AS id",
                    Map.of("srcId", srcId,
                            "tgtId", tgtId,
                            "type", type,
                            "description", nullSafe(description)))
                    .single();
            return record.get("id").asLong();
        });
        recordSchemaRelType(type);
        return id;
    }

    public Option<SchemaSnapshot> readSchema() {
        return write(session -> {
            val result = session.run(
                    "MATCH (s:Schema {id: $id}) RETURN s.labels AS labels, s.relTypes AS relTypes",
                    Map.of("id", SCHEMA_ID));
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

    private void recordSchemaLabel(String label) {
        write(session -> session.run(
                "MERGE (s:Schema {id: $id}) "
                        + "SET s.labels = apoc.coll.union(coalesce(s.labels, []), [$label]), "
                        + "    s.updatedAt = $updatedAt",
                Map.of("id", SCHEMA_ID,
                        "label", label,
                        "updatedAt", Instant.now().toString())));
    }

    private void recordSchemaRelType(String type) {
        write(session -> session.run(
                "MERGE (s:Schema {id: $id}) "
                        + "SET s.relTypes = apoc.coll.union(coalesce(s.relTypes, []), [$type]), "
                        + "    s.updatedAt = $updatedAt",
                Map.of("id", SCHEMA_ID,
                        "type", type,
                        "updatedAt", Instant.now().toString())));
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
