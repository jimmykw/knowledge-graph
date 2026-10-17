package net.jimmykw.knowledgegraph.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import lombok.val;
import net.jimmykw.knowledgegraph.api.ApiExceptionHandler;
import net.jimmykw.knowledgegraph.exception.InvalidPromptException;
import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatControllerTest {

    private ChatService chatService;
    private SchemaService schemaService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        schemaService = mock(SchemaService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(chatService, schemaService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void postReturnsTheChatResponse() throws Exception {
        val response = new ChatResponse("IBM built it", "MATCH (n) RETURN n", List.of(), false, 3, null, List.of("entity-lookup"),
                List.of("MATCH (n) RETURN n"), "c1", List.of(), null, null);
        when(chatService.chat("who?", "c1")).thenReturn(response);

        mvc.perform(post("/api/knowledge-graph/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"who?\",\"conversationId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("IBM built it"))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.conversationId").value("c1"))
                .andExpect(jsonPath("$.skillsExecuted[0]").value("entity-lookup"));
    }

    @Test
    void invalidPromptReturns400WithErrorBody() throws Exception {
        when(chatService.chat(any(), any())).thenThrow(new InvalidPromptException("Prompt must not be blank."));

        mvc.perform(post("/api/knowledge-graph/chat").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Prompt must not be blank."));
    }

    @Test
    void deleteReturns204WhenConversationExisted() throws Exception {
        when(chatService.clearConversation("c1")).thenReturn(true);

        mvc.perform(delete("/api/knowledge-graph/chat").param("conversationId", "c1")).andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenConversationUnknown() throws Exception {
        when(chatService.clearConversation(eq("nope"))).thenReturn(false);

        mvc.perform(delete("/api/knowledge-graph/chat").param("conversationId", "nope")).andExpect(status().isNotFound());
    }

    @Test
    void schemaReturnsTheSnapshot() throws Exception {
        when(schemaService.readSchemaRaw()).thenReturn(new SchemaSnapshot(List.of("Person"), List.of("USES")));

        mvc.perform(get("/api/knowledge-graph/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[0]").value("Person"))
                .andExpect(jsonPath("$.relTypes[0]").value("USES"));
    }
}
