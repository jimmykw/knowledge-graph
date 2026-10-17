package net.jimmykw.knowledgegraph.resolve.cleanup;

import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;

import net.jimmykw.knowledgegraph.graph.CypherIdentifiers;
import net.jimmykw.knowledgegraph.graph.EntityNormalizer;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.GraphEntity;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.MigrationResult;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.PlanEntry;

import io.vavr.Tuple;
import io.vavr.Tuple2;
import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/** Neo4j access for {@code resolveGraph}: the idempotent migration, the reads planning needs, and applying one plan entry. */
@Slf4j
@RequiredArgsConstructor
public class GraphCleanupStore {

    private static final String LABEL_ENTITIES = """
            MATCH (n) WHERE n.name_norm IS NOT NULL AND NOT n:Document AND NOT n:Schema AND NOT n:KgTestInstance AND NOT n:Entity
            SET n:Entity
            RETURN count(n) AS count
            """;

    private static final String UNKEYED_ENTITIES = """
            MATCH (e:Entity) WHERE e.match_key IS NULL OR e.aliases IS NULL OR e.aliases_norm IS NULL OR e.aliases_text IS NULL
            RETURN elementId(e) AS id, e.name AS name, coalesce(e.aliases, [e.name]) AS aliases
            """;

    private static final String SET_KEYS = """
            UNWIND $rows AS r
            MATCH (e) WHERE elementId(e) = r.id
            SET e.match_key = coalesce(e.match_key, r.matchKey), e.aliases = r.aliases, e.aliases_norm = r.aliasesNorm,
                e.aliases_text = r.aliasesText
            """;

    private static final String MENTIONS_FROM_SOURCE_DOC = """
            MATCH (e:Entity) WHERE e.source_doc IS NOT NULL
            MATCH (d:Document {hash: e.source_doc}) WHERE NOT (d)-[:MENTIONS]->(e)
            CREATE (d)-[:MENTIONS {name: e.name, description: coalesce(e.description, '')}]->(e)
            RETURN count(*) AS count
            """;

    private static final String READ_ENTITIES = """
            MATCH (e:Entity)
            RETURN elementId(e) AS id, coalesce(e.name, '') AS name, coalesce(e.label, '') AS label, labels(e) AS labels,
                coalesce(e.description, '') AS description, coalesce(e.aliases, [e.name]) AS aliases,
                COUNT { (e)-[r]-() WHERE type(r) <> 'MENTIONS' } AS degree
            ORDER BY id
            """;

    private static final String FULLTEXT_NEIGHBOURS = """
            UNWIND $probes AS p
            CALL db.index.fulltext.queryNodes('entity_names', p.query, {limit: $limit}) YIELD node, score
            WITH p, node, score WHERE score >= $minScore AND elementId(node) <> p.id
            RETURN p.id AS from, elementId(node) AS to
            """;

    private static final String NEIGHBOURS = """
            UNWIND $ids AS id
            MATCH (e) WHERE elementId(e) = id
            """ + Neo4jGraphWriter.NEIGHBOURS_SUBQUERY + """
            RETURN id, neighbours
            """;

    private static final String CURRENT_NODES = """
            UNWIND $ids AS id
            OPTIONAL MATCH (n) WHERE elementId(n) = id
            RETURN id, n IS NOT NULL AS present, coalesce(n.name, '') AS name, coalesce(labels(n), []) AS labels,
                coalesce(n.aliases, [n.name], []) AS aliases
            """;

    private static final String EDGES_BETWEEN = """
            MATCH (a)-[r]->(b) WHERE elementId(a) IN $ids AND elementId(b) IN $ids
            RETURN a.name + ' -[' + type(r) + ']-> ' + b.name AS edge
            """;

    private static final String MERGE_ENTRY = """
            MATCH (s) WHERE elementId(s) = $survivor
            UNWIND $members AS memberId
            MATCH (m) WHERE elementId(m) = memberId
            WITH s, collect(m) AS ms
            CALL apoc.refactor.mergeNodes([s] + ms, {properties: 'discard', mergeRels: true}) YIELD node
            WITH node, [l IN labels(node) WHERE l <> 'Entity' AND l <> $label] AS extra
            SET node.name = $name, node.name_norm = $nameNorm, node.match_key = $matchKey, node.label = $label,
                node.aliases = $aliases, node.aliases_norm = $aliasesNorm, node.aliases_text = $aliasesText
            WITH node, extra
            CALL apoc.create.removeLabels(node, extra) YIELD node AS stripped
            CALL apoc.create.addLabels(stripped, ['Entity', $label]) YIELD node AS labelled
            RETURN elementId(labelled) AS id
            """;

    private static final String DELETE_SELF_LOOPS = """
            MATCH (n)-[r]->(n) WHERE elementId(n) = $id
            DELETE r
            RETURN count(r) AS count
            """;

    private final Driver driver;
    private final Neo4jGraphWriter writer;

    /**
     * Idempotent, no merges: labels entity nodes {@code :Entity}, fills match key and alias properties where missing, links
     * each node to its {@code source_doc} document with {@code MENTIONS}, and creates the schema.
     */
    public MigrationResult migrate() {
        val labelled = write(session -> session.run(LABEL_ENTITIES).single().get("count").asLong());
        val rows = write(session -> List.ofAll(session.run(UNKEYED_ENTITIES).list()).map(GraphCleanupStore::keyRow));
        rows.grouped(1000).forEach(batch -> write(session -> session.run(SET_KEYS, java.util.Map.of("rows", batch.toJavaList())).consume()));
        val mentions = write(session -> session.run(MENTIONS_FROM_SOURCE_DOC).single().get("count").asLong());
        writer.ensureSchema();
        writer.recordSchema(HashSet.empty(), HashSet.of(Neo4jGraphWriter.MENTIONS));
        log.info("Migration: {} node(s) labelled :Entity, {} keyed, {} MENTIONS edge(s) created", labelled, rows.size(), mentions);
        return new MigrationResult(labelled, rows.size(), mentions);
    }

    private static java.util.Map<String, Object> keyRow(Record row) {
        val name = row.get("name").asString("");
        val aliases = List.ofAll(row.get("aliases").asList(value -> value.asString(""))).prepend(name).filter(alias -> !alias.isEmpty()).distinct();
        return HashMap.<String, Object>of(
                        "id", row.get("id").asString(),
                        "matchKey", EntityNormalizer.matchKey(name),
                        "aliases", aliases.toJavaList(),
                        "aliasesNorm", aliases.map(EntityNormalizer::normalize).distinct().toJavaList(),
                        "aliasesText", aliases.mkString(" | "))
                .toJavaMap();
    }

    public List<GraphEntity> readEntities() {
        return write(session -> List.ofAll(session.run(READ_ENTITIES).list()).map(row -> new GraphEntity(row.get("id").asString(),
                row.get("name").asString(), row.get("label").asString(), row.get("labels").asList(value -> value.asString()),
                row.get("description").asString(), row.get("aliases").asList(value -> value.asString("")), row.get("degree").asInt())));
    }

    /** Full-text neighbour pairs (from, to) per entity, queried in batches; names with nothing to search on are skipped. */
    public List<Tuple2<String, String>> fulltextPairs(List<Tuple2<String, String>> idsAndQueries, int limit, double minScore) {
        return idsAndQueries.grouped(500)
                .flatMap(batch -> write(session -> List.ofAll(session.run(FULLTEXT_NEIGHBOURS, java.util.Map.of(
                                "probes", batch.map(pair -> java.util.Map.of("id", pair._1, "query", pair._2)).toJavaList(),
                                "limit", limit, "minScore", minScore)).list())
                        .map(row -> Tuple.of(row.get("from").asString(), row.get("to").asString()))))
                .toList();
    }

    public Map<String, List<String>> neighbours(List<String> ids) {
        return ids.grouped(500)
                .flatMap(batch -> write(session -> List.ofAll(session.run(NEIGHBOURS, java.util.Map.of("ids", batch.toJavaList())).list())))
                .toMap(row -> row.get("id").asString(), row -> List.ofAll(row.get("neighbours").asList(value -> value.asString(""))));
    }

    /** The current fingerprint of each node, none for a node that no longer exists. */
    public Map<String, Option<String>> currentFingerprints(List<String> ids) {
        return ids.grouped(1000)
                .flatMap(batch -> write(session -> List.ofAll(session.run(CURRENT_NODES, java.util.Map.of("ids", batch.toJavaList())).list())))
                .toMap(row -> row.get("id").asString(), row -> row.get("present").asBoolean()
                        ? Option.some(fingerprint(row.get("name").asString(), row.get("labels").asList(value -> value.asString()),
                                row.get("aliases").asList(value -> value.asString(""))))
                        : Option.none());
    }

    /** What apply compares: display name, every label and every alias, order-independent. */
    public static String fingerprint(String name, java.util.List<String> labels, java.util.List<String> aliases) {
        return name + "|" + List.ofAll(labels).sorted().mkString(",") + "|" + List.ofAll(aliases).sorted().mkString(",");
    }

    /**
     * Applies one entry in its own transaction: merges the members into the survivor (relationships merged, survivor's other
     * properties kept), sets the canonical name, label and aliases, and deletes the self-loops the merge created. Returns the
     * edges that ran between the merged nodes.
     */
    public List<String> applyEntry(PlanEntry entry) {
        if (!CypherIdentifiers.isSafe(entry.label())) {
            throw new IllegalArgumentException("Unsafe label refused: " + entry.label());
        }
        val ids = List.ofAll(entry.nodes()).map(node -> node.elementId());
        return write(session -> session.executeWrite(tx -> {
            val internal = List.ofAll(tx.run(EDGES_BETWEEN, java.util.Map.of("ids", ids.toJavaList())).list())
                    .map(row -> row.get("edge").asString(""));
            val survivor = mergeEntry(tx, entry, ids);
            tx.run(DELETE_SELF_LOOPS, java.util.Map.of("id", survivor)).consume();
            return internal;
        }));
    }

    private static String mergeEntry(TransactionContext tx, PlanEntry entry, List<String> ids) {
        val aliases = List.ofAll(entry.aliases()).distinct();
        val params = HashMap.<String, Object>of(
                        "survivor", entry.survivorId(),
                        "members", ids.remove(entry.survivorId()).toJavaList(),
                        "label", entry.label(),
                        "name", entry.canonicalName(),
                        "nameNorm", EntityNormalizer.normalize(entry.canonicalName()),
                        "matchKey", EntityNormalizer.matchKey(entry.canonicalName()),
                        "aliases", aliases.toJavaList(),
                        "aliasesNorm", aliases.map(EntityNormalizer::normalize).distinct().toJavaList(),
                        "aliasesText", aliases.mkString(" | "))
                .toJavaMap();
        val result = tx.run(MERGE_ENTRY, params).list();
        return List.ofAll(result).headOption().map(row -> row.get("id").asString())
                .getOrElseThrow(() -> new IllegalStateException("Survivor " + entry.survivorId() + " not found"));
    }

    private <T> T write(Function<Session, T> work) {
        return Try.withResources(driver::session).of(work::apply).get();
    }
}
