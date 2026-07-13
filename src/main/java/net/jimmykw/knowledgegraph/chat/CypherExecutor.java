package net.jimmykw.knowledgegraph.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;

import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class CypherExecutor {

    private final Driver driver;
    private final int resultRowLimit;
    private final long queryTimeoutMs;

    public record ExecutedResults(List<Map<String, Object>> rows, int count, boolean truncated) {
    }

    public Try<ExecutedResults> execute(String cypher) {
        log.debug("Executing Cypher (row limit {}): {}", resultRowLimit, cypher);
        val attempt = Try.withResources(() -> driver.session())
                .of(session -> session.executeRead(tx -> runCapped(tx, cypher), transactionConfig()));
        if (attempt.isSuccess()) {
            log.info("Cypher executed: {} row(s){}", attempt.get().count(),
                    attempt.get().truncated() ? " (truncated)" : "");
            return attempt;
        }
        return classifyFailure(attempt.getCause());
    }

    private ExecutedResults runCapped(org.neo4j.driver.TransactionContext tx, String cypher) {
        val result = tx.run(cypher);
        val rows = new ArrayList<Map<String, Object>>();
        int count = 0;
        boolean truncated = false;
        while (result.hasNext()) {
            if (count >= resultRowLimit) {
                truncated = true;
                break;
            }
            rows.add(flatten(result.next()));
            count++;
        }
        return new ExecutedResults(rows, count, truncated);
    }

    private TransactionConfig transactionConfig() {
        return TransactionConfig.builder().withTimeout(Duration.ofMillis(queryTimeoutMs)).build();
    }

    private Try<ExecutedResults> classifyFailure(Throwable cause) {
        if (cause instanceof ServiceUnavailableException || cause instanceof TransientException) {
            return Try.failure(new Neo4jUnavailableException("Neo4j is unavailable: " + cause.getMessage(), cause));
        }
        log.warn("Cypher execution failed: {}", cause.getMessage());
        return Try.failure(cause);
    }

    private Map<String, Object> flatten(Record record) {
        val map = new LinkedHashMap<String, Object>();
        for (val key : record.keys()) {
            map.put(key, convertValue(record.get(key)));
        }
        return map;
    }

    private Object convertValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return convertJavaValue(value.asObject());
    }

    private Object convertJavaValue(Object javaValue) {
        return switch (javaValue) {
            case Node node -> flattenNode(node);
            case Relationship rel -> flattenRelationship(rel, Map.of());
            case Path path -> flattenPath(path);
            case List<?> list -> list.stream().map(this::convertJavaValue).toList();
            case Map<?, ?> map -> flattenMap(map);
            case null -> null;
            default -> javaValue;
        };
    }

    private Map<String, Object> flattenNode(Node node) {
        val map = new LinkedHashMap<String, Object>();
        map.put("__type", "node");
        val labels = new ArrayList<String>();
        node.labels().forEach(labels::add);
        if (!labels.isEmpty()) {
            map.put("label", labels.get(0));
        }
        node.keys().forEach(key -> map.putIfAbsent(key, convertValue(node.get(key))));
        return map;
    }

    private Map<String, Object> flattenRelationship(Relationship rel, Map<String, Node> nodesById) {
        val map = new LinkedHashMap<String, Object>();
        map.put("__type", "rel");
        map.put("type", rel.type());
        val source = nodesById.get(rel.startNodeElementId());
        val target = nodesById.get(rel.endNodeElementId());
        if (source != null) {
            map.put("source", displayName(source));
        }
        if (target != null) {
            map.put("target", displayName(target));
        }
        rel.keys().forEach(key -> map.putIfAbsent(key, convertValue(rel.get(key))));
        return map;
    }

    private List<Object> flattenPath(Path path) {
        val nodesById = new LinkedHashMap<String, Node>();
        path.nodes().forEach(node -> nodesById.put(node.elementId(), node));
        val nodes = new ArrayList<Node>();
        path.nodes().forEach(nodes::add);
        val rels = new ArrayList<Relationship>();
        path.relationships().forEach(rels::add);
        val list = new ArrayList<Object>();
        list.add(flattenNode(nodes.get(0)));
        for (int index = 0; index < rels.size(); index++) {
            list.add(flattenRelationship(rels.get(index), nodesById));
            list.add(flattenNode(nodes.get(index + 1)));
        }
        return list;
    }

    private Map<String, Object> flattenMap(Map<?, ?> map) {
        val result = new LinkedHashMap<String, Object>();
        map.forEach((key, value) -> result.put(String.valueOf(key), convertJavaValue(value)));
        return result;
    }

    private String displayName(Node node) {
        if (node.containsKey("name") && !node.get("name").isNull()) {
            return node.get("name").asString();
        }
        return null;
    }
}
