package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import io.vavr.control.Option;
import lombok.val;
import net.jimmykw.knowledgegraph.exception.GraphEmptyException;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaServiceTest {

    private Neo4jGraphWriter writer;
    private SchemaService service;

    @BeforeEach
    void setUp() {
        writer = mock(Neo4jGraphWriter.class);
        service = new SchemaService(writer);
    }

    @Test
    void chatSchemaFailsWhenNoSchemaNodeExists() {
        when(writer.readSchema()).thenReturn(Option.none());

        assertThatThrownBy(service::readSchemaForChat).isInstanceOf(GraphEmptyException.class);
    }

    @Test
    void chatSchemaFailsWhenThereAreNoLabels() {
        when(writer.readSchema()).thenReturn(Option.some(new SchemaSnapshot(List.of(), List.of("USES"))));

        assertThatThrownBy(service::readSchemaForChat).isInstanceOf(GraphEmptyException.class);
    }

    @Test
    void chatSchemaReturnsTheSnapshot() {
        val snapshot = new SchemaSnapshot(List.of("Person"), List.of("USES"));
        when(writer.readSchema()).thenReturn(Option.some(snapshot));

        assertThat(service.readSchemaForChat()).isEqualTo(snapshot);
    }

    @Test
    void rawSchemaIsEmptyWhenNoSchemaNodeExists() {
        when(writer.readSchema()).thenReturn(Option.none());

        val schema = service.readSchemaRaw();

        assertThat(schema.labels()).isEmpty();
        assertThat(schema.relTypes()).isEmpty();
    }
}
