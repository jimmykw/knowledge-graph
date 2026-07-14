package net.jimmykw.knowledgegraph.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;
import org.springframework.ai.tool.annotation.Tool;

import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
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

    private static final String EXACT_LOOKUP = "MATCH (e) WHERE e.name_norm = toLower($name) AND e.name IS NOT NULL "
            + "RETURN e.name AS name, e.label AS label, e.description AS description LIMIT 1";

    private static final String FUZZY_LOOKUP = "MATCH (e) WHERE e.name_norm CONTAINS toLower($name) AND e.name IS NOT NULL "
            + "RETURN e.name AS name, e.label AS label, e.description AS description "
            + "ORDER BY size(e.name_norm) LIMIT 5";

    private static final String NEIGHBORHOOD_1HOP = "MATCH (e) WHERE e.name_norm = toLower($name) AND e.name IS NOT NULL "
            + "OPTIONAL MATCH (e)-[r]-(neighbor) WHERE id(neighbor) <> id(e) "
            + "RETURN e.name AS entityName, e.label AS entityLabel, "
            + "type(r) AS relType, r.description AS relDesc, "
            + "neighbor.name AS neighborName, neighbor.label AS neighborLabel, "
            + "neighbor.description AS neighborDesc "
            + "ORDER BY relType, neighborLabel";

    private static final String NEIGHBORHOOD_MULTIHOP = "MATCH (e) WHERE e.name_norm = toLower($name) AND e.name IS NOT NULL "
            + "OPTIONAL MATCH path = (e)-[*1..%d]-(neighbor) WHERE id(neighbor) <> id(e) "
            + "RETURN e.name AS entityName, e.label AS entityLabel, "
            + "neighbor.name AS neighborName, neighbor.label AS neighborLabel, "
            + "neighbor.description AS neighborDesc, length(path) AS depth, "
            + "[r IN relationships(path) | type(r)] AS relTypes, "
            + "[r IN relationships(path) | r.description] AS relDescs "
            + "ORDER BY depth, neighborLabel";

    private final SchemaService schemaService;
    private final CypherExecutor cypherExecutor;
    private final Driver driver;
    private final AppProperties appProperties;

    private static final ThreadLocal<ToolTrace> CURRENT_TRACE = new ThreadLocal<>();

    public static void setTrace(ToolTrace trace) {
        CURRENT_TRACE.set(trace);
    }

    public static void clearTrace() {
        CURRENT_TRACE.remove();
    }

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

    @Tool(description = "Find an entity by name (case-insensitive). Returns name, label, and description. "
            + "Falls back to a fuzzy substring match if the exact match fails.")
    public String findEntityByName(String name) {
        if (name == null || name.isBlank()) {
            return errorJson("Entity name must not be blank.");
        }
        return Try.of(() -> rowsJson(findByNameRecords(name)))
                .recover(Throwable.class, failure -> errorJson(failure.getMessage()))
                .get();
    }

    @Tool(description = "Get the k-hop neighborhood of an entity (default 1-hop, max 3). "
            + "Returns the entity plus related neighbors with relationship types and descriptions.")
    public String getEntityNeighborhood(String name, int depth) {
        if (name == null || name.isBlank()) {
            return errorJson("Entity name must not be blank.");
        }
        val clampedDepth = Math.max(1, Math.min(depth, 3));
        return Try.of(() -> rowsJson(neighborhoodRecords(name, clampedDepth)))
                .recover(Throwable.class, failure -> errorJson(failure.getMessage()))
                .get();
    }

    private List<Record> findByNameRecords(String name) {
        val exact = runRead(EXACT_LOOKUP, Map.<String, Object>of("name", name));
        if (!exact.isEmpty()) {
            return exact;
        }
        return runRead(FUZZY_LOOKUP, Map.<String, Object>of("name", name));
    }

    private List<Record> neighborhoodRecords(String name, int depth) {
        val query = depth <= 1 ? NEIGHBORHOOD_1HOP : NEIGHBORHOOD_MULTIHOP.formatted(depth);
        return runRead(query, Map.<String, Object>of("name", name));
    }

    private List<Record> runRead(String cypher, Map<String, Object> params) {
        val trace = CURRENT_TRACE.get();
        if (trace != null) {
            trace.recordCypherQuery(cypher);
        }
        val chat = appProperties.chat();
        return Try.withResources(() -> driver.session())
                .of(session -> session.executeRead(tx -> runCapped(tx, cypher, params), transactionConfig(chat)))
                .getOrElseThrow(this::wrapNeo4jException);
    }

    private List<Record> runCapped(org.neo4j.driver.TransactionContext tx, String cypher, Map<String, Object> params) {
        val result = tx.run(cypher, params);
        val records = new ArrayList<Record>();
        while (result.hasNext()) {
            if (records.size() >= appProperties.chat().resultRowLimit()) {
                break;
            }
            records.add(result.next());
        }
        return records;
    }

    private TransactionConfig transactionConfig(AppProperties.Chat chat) {
        return TransactionConfig.builder().withTimeout(Duration.ofMillis(chat.queryTimeoutMs())).build();
    }

    private RuntimeException wrapNeo4jException(Throwable cause) {
        return switch (cause) {
            case Neo4jUnavailableException neo -> neo;
            case ServiceUnavailableException ignored -> unavailable(cause);
            case TransientException ignored -> unavailable(cause);
            case RuntimeException runtime -> runtime;
            default -> new RuntimeException(cause);
        };
    }

    private static Neo4jUnavailableException unavailable(Throwable cause) {
        return new Neo4jUnavailableException("Neo4j is unavailable: " + cause.getMessage(), cause);
    }

    private static Map<String, Object> schemaMap(SchemaSnapshot schema) {
        return Map.of("labels", schema.labels(), "relTypes", schema.relTypes());
    }

    private String rowsJson(List<Record> records) {
        val rows = records.stream().map(Record::asMap).toList();
        return toJson(Map.of("rows", rows, "count", rows.size()));
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
