package net.jimmykw.knowledgegraph.chat;

import java.util.List;

import net.jimmykw.knowledgegraph.exception.GraphEmptyException;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;

import lombok.RequiredArgsConstructor;
import lombok.val;

@RequiredArgsConstructor
public class SchemaService {

    private final Neo4jGraphWriter writer;

    public SchemaSnapshot readSchemaForChat() {
        val snapshot = writer.readSchema();
        if (snapshot.isEmpty() || snapshot.get().labels().isEmpty()) {
            throw new GraphEmptyException(
                    "The knowledge graph is empty. Ingest a document before chatting.");
        }
        return snapshot.get();
    }

    public SchemaSnapshot readSchemaRaw() {
        return writer.readSchema().getOrElse(new SchemaSnapshot(List.of(), List.of()));
    }
}
