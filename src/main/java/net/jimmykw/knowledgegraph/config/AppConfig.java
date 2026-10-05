package net.jimmykw.knowledgegraph.config;

import org.neo4j.driver.Driver;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.jimmykw.knowledgegraph.chat.ChatService;
import net.jimmykw.knowledgegraph.chat.CypherExecutor;
import net.jimmykw.knowledgegraph.chat.GraphTools;
import net.jimmykw.knowledgegraph.chat.SchemaService;
import net.jimmykw.knowledgegraph.chat.judge.AnswerJudge;
import net.jimmykw.knowledgegraph.chat.judge.JevAnswerJudge;
import net.jimmykw.knowledgegraph.chat.judge.NoOpAnswerJudge;
import net.jimmykw.knowledgegraph.chat.routing.GraphCatalog;
import net.jimmykw.knowledgegraph.chat.routing.GraphProfile;
import net.jimmykw.knowledgegraph.chat.routing.NoOpQuestionRouter;
import net.jimmykw.knowledgegraph.chat.routing.QuestionRouter;
import net.jimmykw.knowledgegraph.chat.routing.TypeSafeIntentClassifier;
import net.jimmykw.knowledgegraph.chat.routing.TypeSafeQuestionRouter;
import net.jimmykw.knowledgegraph.extract.EntityExtractionService;
import net.jimmykw.knowledgegraph.extract.ExtractionPrompter;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;
import net.jimmykw.knowledgegraph.extract.RelationshipExtractionService;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.ingest.PdfIngestionService;

import io.vavr.control.Option;
import lombok.val;

@Configuration
public class AppConfig {

    @Bean
    Neo4jGraphWriter neo4jGraphWriter(Driver driver) {
        return new Neo4jGraphWriter(driver);
    }

    @Bean
    ExtractionPrompter extractionPrompter(ChatClient chatClient, BeanOutputConverter<ExtractionResult> converter) {
        return new ExtractionPrompter(chatClient, converter);
    }

    @Bean
    EntityExtractionService entityExtractionService(ExtractionPrompter prompter, Neo4jGraphWriter writer,
                                                       AppProperties appProperties) {
        return new EntityExtractionService(prompter, writer, appProperties);
    }

    @Bean
    RelationshipExtractionService relationshipExtractionService(ExtractionPrompter prompter, Neo4jGraphWriter writer,
                                                                   AppProperties appProperties) {
        return new RelationshipExtractionService(prompter, writer, appProperties);
    }

    @Bean
    PdfIngestionService pdfIngestionService(Neo4jGraphWriter writer, AppProperties appProperties) {
        return new PdfIngestionService(writer, appProperties);
    }

    @Bean
    SchemaService schemaService(Neo4jGraphWriter writer) {
        return new SchemaService(writer);
    }

    @Bean
    CypherExecutor cypherExecutor(Driver driver, AppProperties appProperties) {
        val chat = appProperties.chat();
        return new CypherExecutor(driver, chat.resultRowLimit(), chat.queryTimeoutMs());
    }

    @Bean
    GraphTools graphTools(SchemaService schemaService, CypherExecutor cypherExecutor) {
        return new GraphTools(schemaService, cypherExecutor);
    }

    @Bean
    QuestionRouter questionRouter(AppProperties appProperties, ObjectProvider<TypeSafeClient> client, Neo4jGraphWriter writer) {
        val routing = appProperties.routing();
        return routing.enabled()
                ? new TypeSafeQuestionRouter(new TypeSafeIntentClassifier(requireClient(client, "app.routing.enabled"),
                        warmed(new GraphCatalog(() -> graphProfile(writer)))), routing.blockThreshold())
                : new NoOpQuestionRouter();
    }

    @Bean
    AnswerJudge answerJudge(AppProperties appProperties, ObjectProvider<TypeSafeClient> client) {
        val judge = appProperties.judge();
        return judge.enabled()
                ? new JevAnswerJudge(JevAnswerJudge.buildJudge(requireClient(client, "app.judge.enabled")), judge.maxEvidenceChars())
                : new NoOpAnswerJudge();
    }

    /** The starter only creates the client when spring.ai.typesafe.api-key is set; a feature that needs it must not start without it. */
    static TypeSafeClient requireClient(ObjectProvider<TypeSafeClient> client, String flag) {
        return Option.of(client.getIfAvailable())
                .getOrElseThrow(() -> new IllegalStateException(flag + "=true needs a TypeSafe client, but spring.ai.typesafe.api-key is not set"));
    }

    private static GraphProfile graphProfile(Neo4jGraphWriter writer) {
        return new GraphProfile(writer.readDocumentNames(), writer.readTopEntityTypes(10), writer.readTopEntityNames(30));
    }

    /** Loads the profile once at startup (failures are logged and retried on the next call). */
    private static GraphCatalog warmed(GraphCatalog catalog) {
        catalog.get();
        return catalog;
    }

    @Bean
    ChatService chatService(ChatClient chatChatClient, ChatClient directChatClient, AppProperties appProperties, ChatMemory chatMemory,
                            QuestionRouter questionRouter, AnswerJudge answerJudge) {
        return new ChatService(chatChatClient, directChatClient, appProperties, chatMemory, questionRouter, answerJudge);
    }
}
