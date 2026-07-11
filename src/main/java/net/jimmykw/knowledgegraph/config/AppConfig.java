package net.jimmykw.knowledgegraph.config;

import org.neo4j.driver.Driver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import net.jimmykw.knowledgegraph.extract.EntityExtractionService;
import net.jimmykw.knowledgegraph.extract.ExtractionPrompter;
import net.jimmykw.knowledgegraph.extract.ExtractionRecords.ExtractionResult;
import net.jimmykw.knowledgegraph.extract.RelationshipExtractionService;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.ingest.PdfIngestionService;

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
}
