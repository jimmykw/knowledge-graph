package net.jimmykw.knowledgegraph.config;

import java.util.Locale;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;

import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    Driver neo4jDriver(@Value("${spring.neo4j.uri}") String uri,
                       @Value("${spring.neo4j.authentication.username}") String username,
                       @Value("${spring.neo4j.authentication.password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }

    @Bean
    ApplicationRunner apocProbe(Driver driver) {
        return args -> probeApoc(driver);
    }

    private void probeApoc(Driver driver) {
        Try.withResources(() -> driver.session())
                .of(session -> session.run("RETURN apoc.version() AS v").consume())
                .onSuccess(ignored -> log.info("Neo4j connection established and APOC is available."))
                .onFailure(this::handleProbeFailure);
    }

    private void handleProbeFailure(Throwable cause) {
        switch (cause) {
            case ServiceUnavailableException ignored -> logNeo4jUnavailable(cause);
            case TransientException ignored -> logNeo4jUnavailable(cause);
            case Exception e when isApocError(e) ->
                    throw new Neo4jUnavailableException(apocMissingMessage(), e);
            default -> log.warn("Neo4j APOC probe failed ({}: {}). The endpoint will return 503 until Neo4j/APOC is available.",
                    cause.getClass().getSimpleName(), cause.getMessage());
        }
    }

    private void logNeo4jUnavailable(Throwable cause) {
        log.warn("Neo4j unreachable at startup ({}: {}). The endpoint will return 503 until Neo4j is available.",
                cause.getClass().getSimpleName(), cause.getMessage());
    }

    private static boolean isApocError(Throwable cause) {
        val message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("apoc") || message.contains("procedure");
    }

    private static String apocMissingMessage() {
        return "APOC is required but was not found on the Neo4j instance. "
                + "Install the APOC plugin, restart Neo4j, then start this application.";
    }
}
