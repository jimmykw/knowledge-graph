package net.jimmykw.knowledgegraph.testsupport;

import java.net.URI;

import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.val;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/**
 * Base class for the opt-in real-Neo4j tests ({@code ./gradlew neo4jIt}). It only ever wipes a database that is positively
 * marked as the throwaway instance: the port must not be the real graph's (7687) and a single
 * {@code (:KgTestInstance {id: 'kg-throwaway'})} node must exist. A failed check fails the class; it never skips.
 * Subclasses must carry {@code @EnabledIfSystemProperty(named = "neo4j.it", matches = "true")} themselves (JUnit does not inherit it).
 */
public abstract class Neo4jItSupport {

    public static final String SENTINEL_ID = "kg-throwaway";
    public static final int REAL_GRAPH_PORT = 7687;

    private static final String DEFAULT_URI = "bolt://localhost:7688";
    private static final String DEFAULT_USER = "neo4j";
    private static final String DEFAULT_PASSWORD = "neo4j-admin";
    private static final String NOT_THROWAWAY = "Neo4j IT target %s is not the marked throwaway instance; see AGENTS.md";

    protected static Driver driver;

    @BeforeAll
    static void connectAndGuard() {
        val uri = Option.of(System.getenv("NEO4J_IT_URI")).getOrElse(DEFAULT_URI);
        val user = Option.of(System.getenv("NEO4J_IT_USER")).getOrElse(DEFAULT_USER);
        val password = Option.of(System.getenv("NEO4J_IT_PASSWORD")).getOrElse(DEFAULT_PASSWORD);
        assertSafeTarget(uri);
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        Try.run(() -> driver.verifyConnectivity()).getOrElseThrow(e -> new IllegalStateException("cannot connect to " + uri, e));
        assertSentinel(uri);
        assertApoc();
    }

    @AfterAll
    static void closeDriver() {
        Option.of(driver).forEach(Driver::close);
        driver = null;
    }

    /** Every test starts from an empty graph plus the sentinel. Only runs after the guard in {@link #connectAndGuard()} has passed. */
    @BeforeEach
    void wipe() {
        Try.withResources(() -> driver.session())
                .of(session -> session.run("MATCH (n) WHERE NOT n:KgTestInstance DETACH DELETE n").consume())
                .get();
    }

    /** Refuses the real graph's port (bolt's default port when none is given). */
    public static void assertSafeTarget(String uri) {
        val parsed = Try.of(() -> URI.create(uri)).getOrElseThrow(e -> new IllegalStateException(NOT_THROWAWAY.formatted(uri), e));
        val port = parsed.getPort() == -1 ? REAL_GRAPH_PORT : parsed.getPort();
        if (port == REAL_GRAPH_PORT) {
            throw new IllegalStateException("Refusing to run against port " + REAL_GRAPH_PORT + " (the real graph): " + uri);
        }
    }

    private static void assertSentinel(String uri) {
        val count = Try.withResources(() -> driver.session())
                .of(session -> session.run("MATCH (s:KgTestInstance {id: $id}) RETURN count(s) AS c", java.util.Map.of("id", SENTINEL_ID))
                        .single().get("c").asLong())
                .get();
        if (count != 1L) {
            throw new IllegalStateException(NOT_THROWAWAY.formatted(uri));
        }
    }

    private static void assertApoc() {
        Try.withResources(() -> driver.session())
                .of(session -> session.run("RETURN apoc.version() AS v").single().get("v").asString())
                .getOrElseThrow(e -> new IllegalStateException("APOC is not available on the Neo4j IT target", e));
    }

    protected static long countNodes(String cypher) {
        return countNodes(cypher, java.util.Map.of());
    }

    protected static long countNodes(String cypher, java.util.Map<String, Object> params) {
        return Try.withResources(() -> driver.session())
                .of(session -> session.run(cypher, params).single().get(0).asLong())
                .get();
    }
}
