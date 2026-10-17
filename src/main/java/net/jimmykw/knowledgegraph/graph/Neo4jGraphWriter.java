package net.jimmykw.knowledgegraph.graph;

import java.time.Instant;
import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;

import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractedEntity;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ResolvedRelationship;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Candidate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.EntityProbe;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.Mention;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NewNode;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.NodeUpdate;
import net.jimmykw.knowledgegraph.resolve.ResolutionRecords.ResolutionPlan;

import io.vavr.collection.HashMap;
import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.collection.Set;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class Neo4jGraphWriter {

    static final String SCHEMA_ID = "default";

    public static final String ENTITY_LABEL = "Entity";
    public static final String MENTIONS = "MENTIONS";

    static final List<String> SCHEMA_STATEMENTS = List.of(
            "CREATE CONSTRAINT document_hash IF NOT EXISTS FOR (d:Document) REQUIRE d.hash IS UNIQUE",
            "CREATE INDEX entity_name_norm IF NOT EXISTS FOR (e:Entity) ON (e.name_norm)",
            "CREATE INDEX entity_match_key IF NOT EXISTS FOR (e:Entity) ON (e.match_key)",
            "CREATE FULLTEXT INDEX entity_names IF NOT EXISTS FOR (e:Entity) ON EACH [e.name, e.aliases_text]");

    private static final String MERGE_ENTITIES_QUERY = """
            UNWIND $entities AS e
            CALL apoc.merge.node([e.label], {name_norm: e.nameNorm},
                {name: e.name, label: e.label, description: e.description, source_doc: $hash}, {})
            YIELD node
            RETURN e.idx AS idx, elementId(node) AS id
            """;

    private static final String MERGE_RELATIONSHIPS_QUERY = """
            UNWIND $rels AS r
            MATCH (a), (b) WHERE elementId(a) = r.srcId AND elementId(b) = r.tgtId
            CALL apoc.merge.relationship(a, r.type, {description: r.description}, {}, b, {})
            YIELD rel
            RETURN r.idx AS idx, elementId(rel) AS id
            """;

    /** Up to 5 edges of node {@code e} as {@code -[TYPE]-> name} / {@code <-[TYPE]- name}, leaving out documents: {@code neighbours}. */
    public static final String NEIGHBOURS_SUBQUERY = """
            CALL (e) {
                OPTIONAL MATCH (e)-[r]-(o) WHERE NOT o:Document
                WITH e, r, o LIMIT 5
                RETURN collect(CASE WHEN startNode(r) = e THEN '-[' + type(r) + ']-> ' ELSE '<-[' + type(r) + ']- ' END
                    + coalesce(o.name, '')) AS neighbours
            }
            """;

    /** Exact (name, alias, match key) and full-text candidates per probe, each with up to 5 neighbour edges. */
    static final String FIND_CANDIDATES_QUERY = """
            UNWIND $probes AS p
            CALL (p) {
                MATCH (e:Entity)
                WHERE e.name_norm = p.nameNorm OR p.nameNorm IN e.aliases_norm OR e.match_key = p.matchKey
                RETURN collect(e) AS exact
            }
            CALL (p) {
                WITH p WHERE p.query IS NOT NULL
                CALL db.index.fulltext.queryNodes('entity_names', p.query, {limit: $limit}) YIELD node, score
                WHERE score >= $minScore
                RETURN collect(node) AS fuzzy
            }
            UNWIND [e IN exact | {node: e, exact: true}] + [e IN fuzzy WHERE NOT e IN exact | {node: e, exact: false}] AS c
            WITH p, c.node AS e, c.exact AS exact
            """ + NEIGHBOURS_SUBQUERY + """
            RETURN p.idx AS idx, elementId(e) AS id, e.name AS name, e.label AS label, coalesce(e.description, '') AS description,
                coalesce(e.aliases, [e.name]) AS aliases, exact, neighbours
            """;

    private static final String CREATE_ENTITIES_QUERY = """
            UNWIND $nodes AS n
            CALL apoc.create.node(['Entity', n.label], {name: n.name, name_norm: n.nameNorm, match_key: n.matchKey,
                aliases: n.aliases, aliases_norm: n.aliasesNorm, aliases_text: n.aliasesText, label: n.label,
                description: n.description, source_doc: $hash})
            YIELD node
            RETURN n.key AS key, elementId(node) AS id
            """;

    private static final String UPDATE_ENTITIES_QUERY = """
            UNWIND $updates AS u
            MATCH (e) WHERE elementId(e) = u.id
            WITH e, u, apoc.coll.toSet(coalesce(e.aliases, [e.name]) + u.aliases) AS aliases,
                apoc.coll.toSet(coalesce(e.aliases_norm, [e.name_norm]) + u.aliasesNorm) AS aliasesNorm
            SET e.name = u.name, e.name_norm = u.nameNorm, e.match_key = u.matchKey, e.label = u.label,
                e.aliases = aliases, e.aliases_norm = aliasesNorm, e.aliases_text = apoc.text.join(aliases, ' | ')
            WITH e, u
            CALL apoc.create.removeLabels(e, u.removeLabels) YIELD node AS stripped
            CALL apoc.create.addLabels(stripped, ['Entity', u.label]) YIELD node
            RETURN u.id AS id
            """;

    private static final String MERGE_MENTIONS_QUERY = """
            UNWIND $mentions AS x
            MATCH (d:Document {hash: $hash})
            MATCH (e) WHERE elementId(e) = x.id
            MERGE (d)-[m:MENTIONS]->(e)
            ON CREATE SET m.name = x.name, m.description = x.description
            """;

    private final Driver driver;

    public Option<String> findDocumentId(String hash) {
        return write(session -> {
            val result = session.run("MATCH (d:Document {hash: $hash}) RETURN elementId(d) AS id",
                    java.util.Map.of("hash", hash));
            return result.hasNext() ? Option.some(result.next().get("id").asString()) : Option.none();
        });
    }

    /** Filenames of the ingested {@code :Document} nodes, oldest first. */
    public List<String> readDocumentNames() {
        return write(session -> List.ofAll(session.run(
                        "MATCH (d:Document) WHERE d.filename IS NOT NULL AND d.filename <> '' "
                                + "RETURN d.filename AS name ORDER BY d.uploadedAt").list())
                .map(row -> row.get("name").asString()));
    }

    /** Entity labels, most numerous first (excludes {@code :Document}/{@code :Schema} nodes and the shared {@code :Entity} label). */
    public List<String> readTopEntityTypes(int limit) {
        return write(session -> List.ofAll(session.run(
                        "MATCH (n) WHERE NOT n:Document AND NOT n:Schema UNWIND labels(n) AS label WITH label WHERE label <> 'Entity' "
                                + "RETURN label, count(*) AS entities ORDER BY entities DESC LIMIT $limit",
                        java.util.Map.of("limit", limit)).list())
                .map(row -> row.get("label").asString()));
    }

    /** Names of the most-connected entities ({@code MENTIONS} edges not counted), most connected first; may repeat a name. */
    public List<String> readTopEntityNames(int limit) {
        return write(session -> List.ofAll(session.run(
                        "MATCH (n) WHERE NOT n:Document AND NOT n:Schema AND n.name IS NOT NULL "
                                + "WITH n, COUNT { (n)-[r]-() WHERE type(r) <> 'MENTIONS' } AS degree "
                                + "ORDER BY degree DESC LIMIT $limit RETURN n.name AS name",
                        java.util.Map.of("limit", limit)).list())
                .map(row -> row.get("name").asString()));
    }

    public String mergeDocument(String hash, String filename) {
        val id = write(session -> session.run(
                        "MERGE (d:Document {hash: $hash}) "
                                + "SET d.filename = $filename, d.uploadedAt = $uploadedAt "
                                + "RETURN elementId(d) AS id",
                        java.util.Map.of("hash", hash,
                                "filename", nullSafe(filename),
                                "uploadedAt", Instant.now().toString()))
                .single()
                .get("id")
                .asString());
        recordSchema(HashSet.of("Document"), HashSet.empty());
        return id;
    }

    public Map<Integer, String> mergeEntities(List<ExtractedEntity> entities, String hash) {
        if (entities.isEmpty()) {
            return HashMap.empty();
        }
        requireSafe(entities.map(ExtractedEntity::label), "label");
        return write(session -> {
            val params = java.util.Map.of("entities", entityParams(entities), "hash", hash);
            return idsByIdx(session.run(MERGE_ENTITIES_QUERY, params).list());
        });
    }

    public Map<Integer, String> mergeRelationships(List<ResolvedRelationship> relationships) {
        if (relationships.isEmpty()) {
            return HashMap.empty();
        }
        requireSafe(relationships.map(ResolvedRelationship::type), "relationship type");
        return write(session -> idsByIdx(session.run(MERGE_RELATIONSHIPS_QUERY,
                java.util.Map.of("rels", relationshipParams(relationships))).list()));
    }

    /**
     * Creates the constraint and indexes entity resolution relies on (idempotent) and waits for them to come online. Each
     * statement runs on its own: one that fails (for example the document constraint over existing duplicates) is logged and
     * the rest still run.
     */
    public void ensureSchema() {
        SCHEMA_STATEMENTS.forEach(statement -> Try.run(() -> write(session -> session.run(statement).consume()))
                .onFailure(failure -> log.warn("Schema statement failed ({}): {}", failure.getMessage(), statement)));
        write(session -> session.run("CALL db.awaitIndexes(300)").consume());
        log.info("Entity resolution schema is in place");
    }

    /** Candidate {@code :Entity} nodes per probe idx; a probe with no candidates is absent from the map. */
    public Map<Integer, List<Candidate>> findCandidates(List<EntityProbe> probes, int limit, double minScore) {
        if (probes.isEmpty()) {
            return HashMap.empty();
        }
        val params = java.util.Map.<String, Object>of("probes", probes.map(Neo4jGraphWriter::probeParam).toJavaList(),
                "limit", limit, "minScore", minScore);
        return write(session -> List.ofAll(session.run(FIND_CANDIDATES_QUERY, params).list())
                .map(Neo4jGraphWriter::candidate)
                .groupBy(Candidate::idx));
    }

    /**
     * Writes a resolution plan in one transaction: creates the new nodes, updates the existing ones (name, aliases, label) and
     * links every mention to its node from the document. Returns entity idx to node elementId.
     */
    public Map<Integer, String> applyResolution(ResolutionPlan plan, String hash) {
        requireSafe(plan.newNodes().map(NewNode::label).appendAll(plan.updates().map(NodeUpdate::label)), "label");
        return writeTransaction(tx -> {
            val created = createEntities(tx, plan.newNodes(), hash);
            updateEntities(tx, plan.updates());
            val ids = plan.mentions().toMap(Mention::idx, mention -> mention.target().fold(key -> createdId(created, key), id -> id));
            mergeMentions(tx, plan.mentions(), ids, hash);
            return ids;
        });
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

    private static Map<Integer, String> createEntities(TransactionContext tx, List<NewNode> nodes, String hash) {
        if (nodes.isEmpty()) {
            return HashMap.empty();
        }
        val params = java.util.Map.<String, Object>of("nodes", nodes.map(Neo4jGraphWriter::newNodeParam).toJavaList(), "hash", hash);
        return List.ofAll(tx.run(CREATE_ENTITIES_QUERY, params).list())
                .toMap(row -> row.get("key").asInt(), row -> row.get("id").asString());
    }

    private static void updateEntities(TransactionContext tx, List<NodeUpdate> updates) {
        if (updates.nonEmpty()) {
            tx.run(UPDATE_ENTITIES_QUERY, java.util.Map.of("updates", updates.map(Neo4jGraphWriter::updateParam).toJavaList())).consume();
        }
    }

    private static void mergeMentions(TransactionContext tx, List<Mention> mentions, Map<Integer, String> ids, String hash) {
        if (mentions.nonEmpty()) {
            val params = mentions.map(mention -> mentionParam(mention, ids)).toJavaList();
            tx.run(MERGE_MENTIONS_QUERY, java.util.Map.of("mentions", params, "hash", hash)).consume();
        }
    }

    private static String createdId(Map<Integer, String> created, int key) {
        return created.get(key).getOrElseThrow(() -> new IllegalStateException("Neo4j returned no node id for new entity #" + key));
    }

    private static Map<Integer, String> idsByIdx(java.util.List<Record> rows) {
        return List.ofAll(rows).toMap(row -> row.get("idx").asInt(), row -> row.get("id").asString());
    }

    private static Candidate candidate(Record row) {
        return new Candidate(row.get("idx").asInt(), row.get("id").asString(), row.get("name").asString(""),
                row.get("label").asString(""), row.get("description").asString(""),
                List.ofAll(row.get("aliases").asList(value -> value.asString(""))),
                List.ofAll(row.get("neighbours").asList(value -> value.asString(""))), row.get("exact").asBoolean());
    }

    private static java.util.Map<String, Object> probeParam(EntityProbe probe) {
        val params = new java.util.HashMap<String, Object>();
        params.put("idx", probe.idx());
        params.put("nameNorm", probe.nameNorm());
        params.put("matchKey", probe.matchKey());
        params.put("query", probe.fulltextQuery().getOrNull());
        return params;
    }

    private static java.util.Map<String, Object> newNodeParam(NewNode node) {
        return HashMap.<String, Object>of(
                        "key", node.key(),
                        "name", node.name(),
                        "nameNorm", EntityNormalizer.normalize(node.name()),
                        "matchKey", EntityNormalizer.matchKey(node.name()),
                        "label", node.label(),
                        "description", nullSafe(node.description()))
                .merge(aliasParams(node.aliases()))
                .toJavaMap();
    }

    private static java.util.Map<String, Object> updateParam(NodeUpdate update) {
        val removeLabels = update.oldLabel().equals(update.label()) || update.oldLabel().equals(ENTITY_LABEL)
                ? java.util.List.<String>of()
                : java.util.List.of(update.oldLabel());
        return HashMap.<String, Object>of(
                        "id", update.elementId(),
                        "name", update.name(),
                        "nameNorm", EntityNormalizer.normalize(update.name()),
                        "matchKey", EntityNormalizer.matchKey(update.name()),
                        "label", update.label(),
                        "removeLabels", removeLabels)
                .merge(aliasParams(update.aliases()))
                .toJavaMap();
    }

    private static HashMap<String, Object> aliasParams(List<String> aliases) {
        val distinct = aliases.distinct();
        return HashMap.of(
                "aliases", distinct.toJavaList(),
                "aliasesNorm", distinct.map(EntityNormalizer::normalize).distinct().toJavaList(),
                "aliasesText", distinct.mkString(" | "));
    }

    private static java.util.Map<String, Object> mentionParam(Mention mention, Map<Integer, String> ids) {
        return HashMap.<String, Object>of(
                        "id", ids.get(mention.idx()).getOrElseThrow(() -> new IllegalStateException("No node for mention #" + mention.idx())),
                        "name", mention.name(),
                        "description", nullSafe(mention.description()))
                .toJavaMap();
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

    /** APOC does not escape identifiers, so anything that could break out of the backticks is refused before any query runs. */
    private static void requireSafe(List<String> identifiers, String kind) {
        identifiers.reject(CypherIdentifiers::isSafe).headOption().forEach(bad -> {
            throw new IllegalArgumentException("Unsafe " + kind + " refused: " + bad);
        });
    }

    private <T> T write(Function<Session, T> work) {
        return Try.withResources(() -> driver.session())
                .of(work::apply)
                .getOrElseThrow(this::wrapNeo4jException);
    }

    private <T> T writeTransaction(Function<TransactionContext, T> work) {
        return write(session -> session.executeWrite(work::apply));
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
