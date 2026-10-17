package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vavr.collection.HashSet;
import lombok.val;
import net.jimmykw.knowledgegraph.exception.GraphEmptyException;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.testsupport.Neo4jItSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "neo4j.it", matches = "true")
class SchemaServiceIT extends Neo4jItSupport {

    @Test
    void emptyGraphReadsAsEmptyRawSchemaAndFailsForChat() {
        val service = new SchemaService(new Neo4jGraphWriter(driver));

        assertThat(service.readSchemaRaw().labels()).isEmpty();
        assertThatThrownBy(service::readSchemaForChat).isInstanceOf(GraphEmptyException.class);
    }

    @Test
    void recordedSchemaIsReadBack() {
        val writer = new Neo4jGraphWriter(driver);
        writer.recordSchema(HashSet.of("Person"), HashSet.of("USES"));

        val schema = new SchemaService(writer).readSchemaForChat();

        assertThat(schema.labels()).containsExactly("Person");
        assertThat(schema.relTypes()).containsExactly("USES");
    }
}
