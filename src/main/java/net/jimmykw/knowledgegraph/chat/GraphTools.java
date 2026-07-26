package net.jimmykw.knowledgegraph.chat;

import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;

import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;

import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@RequiredArgsConstructor
public class GraphTools {

    private static final JsonMapper JSON = JsonMapper.shared();

    private static final Pattern WRITE_PATTERN = Pattern.compile(
            "\\b(CREATE|MERGE|SET\\b|DELETE|REMOVE|DROP|CALL\\s+apoc\\.(merge|create))\\b",
            Pattern.CASE_INSENSITIVE);

    private final SchemaService schemaService;
    private final CypherExecutor cypherExecutor;

    @Tool(description = "Get the knowledge graph schema: node labels and relationship types. "
            + "Call this first to learn which labels and relationship types exist before writing Cypher.")
    public String getGraphSchema() {
        return Try.of(() -> {
            val schema = schemaService.readSchemaRaw();
            return toJson(schemaMap(schema));
        }).recover(Throwable.class, failure -> errorJson(failure.getMessage())).get();
    }

    @Tool(description = "Execute a read-only Cypher query against the knowledge graph. "
            + "Returns JSON with rows, count, and truncated flag. "
            + "Write operations (CREATE, MERGE, SET, DELETE, REMOVE, DROP, CALL apoc.merge, CALL apoc.create) "
            + "are blocked. Match entities on name_norm = toLower($name) for case-insensitive lookups.")
    public String runReadCypher(String cypher) {
        if (cypher == null || cypher.isBlank()) {
            return errorJson("Cypher query must not be blank.");
        }
        if (WRITE_PATTERN.matcher(cypher).find()) {
            return errorJson("Error: write operations are not allowed. Only read-only Cypher is permitted.");
        }
        val executed = cypherExecutor.execute(cypher);
        if (executed.isFailure()) {
            return errorJson(executed.getCause().getMessage());
        }
        val results = executed.get();
        return toJson(Map.of("rows", results.rows(), "count", results.count(), "truncated", results.truncated()));
    }

    private static Map<String, Object> schemaMap(SchemaSnapshot schema) {
        return Map.of("labels", schema.labels(), "relTypes", schema.relTypes());
    }

    private static String toJson(Object value) {
        return Try.of(() -> JSON.writeValueAsString(value))
                .recover(Throwable.class, failure -> {
                    log.warn("Failed to serialize tool result to JSON: {}", failure.getMessage());
                    return "{\"error\":\"Failed to serialize result to JSON\"}";
                })
                .get();
    }

    private static String errorJson(String message) {
        return toJson(Map.of("error", message == null ? "" : message));
    }
}