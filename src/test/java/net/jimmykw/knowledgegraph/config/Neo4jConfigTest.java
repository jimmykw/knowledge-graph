package net.jimmykw.knowledgegraph.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import lombok.val;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.TransientException;
import org.springframework.boot.ApplicationRunner;

class Neo4jConfigTest {

    private Driver driver;
    private Session session;
    private ApplicationRunner probe;

    @BeforeEach
    void setUp() {
        driver = mock(Driver.class);
        session = mock(Session.class);
        when(driver.session()).thenReturn(session);
        probe = new Neo4jConfig().apocProbe(driver);
    }

    @Test
    void successDoesNotThrow() {
        when(session.run(anyString())).thenReturn(mock(Result.class));

        assertThatCode(() -> probe.run(null)).doesNotThrowAnyException();
    }

    @Test
    void unreachableNeo4jIsOnlyWarned() {
        when(session.run(anyString())).thenThrow(new ServiceUnavailableException("no route"));

        assertThatCode(() -> probe.run(null)).doesNotThrowAnyException();
    }

    @Test
    void transientErrorIsOnlyWarned() {
        when(session.run(anyString())).thenThrow(new TransientException("Neo.TransientError.General", "busy"));

        assertThatCode(() -> probe.run(null)).doesNotThrowAnyException();
    }

    @Test
    void missingApocFailsStartup() {
        when(session.run(anyString())).thenThrow(new ClientException("Neo.ClientError.Statement.SyntaxError", "Unknown function 'apoc.version'"));

        assertThatThrownBy(() -> probe.run(null))
                .isInstanceOf(Neo4jUnavailableException.class)
                .hasMessageContaining("APOC is required");
    }

    @Test
    void missingProcedureFailsStartup() {
        when(session.run(anyString())).thenThrow(new ClientException("Neo.ClientError.Procedure.ProcedureNotFound", "There is no procedure"));

        assertThatThrownBy(() -> probe.run(null)).isInstanceOf(Neo4jUnavailableException.class);
    }

    @Test
    void otherErrorsAreOnlyWarned() {
        when(session.run(anyString())).thenThrow(new ClientException("Neo.ClientError.Security.Unauthorized", "bad credentials"));
        val runner = probe;

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }
}
