package net.jimmykw.knowledgegraph.resolve;

import java.nio.file.Path;
import java.util.concurrent.Executors;

import org.neo4j.driver.Driver;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import net.jimmykw.knowledgegraph.KnowledgeGraphApplication;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.graph.Neo4jGraphWriter;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupPlanner;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupRecords.ClusterResponse;
import net.jimmykw.knowledgegraph.resolve.cleanup.CleanupReports;
import net.jimmykw.knowledgegraph.resolve.cleanup.ClusterPrompter;
import net.jimmykw.knowledgegraph.resolve.cleanup.GraphCleanupStore;
import net.jimmykw.knowledgegraph.resolve.cleanup.ResolveGraph;
import net.jimmykw.knowledgegraph.util.JsonPrompter;

import io.vavr.collection.List;
import io.vavr.control.Option;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * {@code ./gradlew resolveGraph}: the one-time cleanup of duplicates already in the graph. Starts the app context without the
 * web server, the intent gate or the judge (and with resolution on), then plans by default or applies with
 * {@code -Dresolve.apply=true -Dresolve.backupConfirmed=true}. Other settings: {@code -Dresolve.dir} (default
 * build/reports/resolve), {@code -Dresolve.minFulltextScore}, {@code -Dresolve.candidateLimit}, {@code -Dresolve.maxClusterSize}.
 */
@Slf4j
public class ResolveGraphCommand {

    static final List<String> OVERRIDES = List.of(
            "--spring.main.web-application-type=none",
            "--app.routing.enabled=false",
            "--app.judge.enabled=false",
            "--app.resolution.enabled=true",
            // Keeps the chat-memory H2 file free for a running app.
            "--spring.datasource.url=jdbc:h2:mem:resolve-graph");

    public static void main(String[] args) {
        val context = new SpringApplicationBuilder(KnowledgeGraphApplication.class)
                .run(List.of(args).appendAll(OVERRIDES).toJavaArray(String[]::new));
        val exitCode = Try.run(() -> run(context))
                .onFailure(failure -> log.error("resolveGraph failed: {}", failure.getMessage(), failure))
                .map(ignored -> 0)
                .getOrElse(1);
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private static void run(ConfigurableApplicationContext context) {
        val properties = context.getBean(AppProperties.class);
        val resolution = properties.resolution();
        val store = new GraphCleanupStore(context.getBean(Driver.class), context.getBean(Neo4jGraphWriter.class));
        val reports = new CleanupReports(Path.of(System.getProperty("resolve.dir", "build/reports/resolve")));
        val executor = Executors.newFixedThreadPool(properties.models().extract().poolSize());
        val chatClient = context.getBean("chatClient", ChatClient.class);
        val planner = new CleanupPlanner(store,
                new ClusterPrompter(new JsonPrompter<>(chatClient, new BeanOutputConverter<>(ClusterResponse.class))), executor,
                intProperty("resolve.candidateLimit", resolution.candidateLimit()),
                doubleProperty("resolve.minFulltextScore", resolution.minFulltextScore()),
                intProperty("resolve.maxClusterSize", resolution.batchSize()));
        val resolveGraph = new ResolveGraph(store, planner, reports);
        Try.run(() -> {
            if (Boolean.getBoolean("resolve.apply")) {
                resolveGraph.apply(Boolean.getBoolean("resolve.backupConfirmed"));
            } else {
                resolveGraph.plan();
            }
        }).andFinally(executor::shutdownNow).get();
    }

    private static int intProperty(String name, int fallback) {
        return Option.of(System.getProperty(name)).map(Integer::parseInt).getOrElse(fallback);
    }

    private static double doubleProperty(String name, double fallback) {
        return Option.of(System.getProperty(name)).map(Double::parseDouble).getOrElse(fallback);
    }
}
