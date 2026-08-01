package net.jimmykw.knowledgegraph.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final SchemaService schemaService;

    @PostMapping("/api/knowledge-graph/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.prompt(), request.conversationId());
    }

    @DeleteMapping("/api/knowledge-graph/chat")
    public ResponseEntity<Void> deleteConversation(@RequestParam String conversationId) {
        return chatService.clearConversation(conversationId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/knowledge-graph/schema")
    public SchemaSnapshot schema() {
        return schemaService.readSchemaRaw();
    }
}