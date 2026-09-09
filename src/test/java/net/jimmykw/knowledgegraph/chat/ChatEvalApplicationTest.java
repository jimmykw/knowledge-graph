package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.neo4j.driver.Driver;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import net.jimmykw.knowledgegraph.chat.eval.EvalJudge;
import net.jimmykw.knowledgegraph.chat.eval.EvalJudgeConfig;
import net.jimmykw.knowledgegraph.chat.eval.GoldenCase;
import net.jimmykw.knowledgegraph.chat.eval.GoldenCases;
import net.jimmykw.knowledgegraph.chat.eval.KnowledgeGraphJudgeEvaluator;
import net.jimmykw.knowledgegraph.chat.eval.TransportRetry;

import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real-model eval over {@link GoldenCases}. Invariants and judge context are aggregated over every
 * {@code runReadCypher} call of every turn via {@link ChatResponse#evidence()}: the last query is
 * often a small bookkeeping lookup (e.g. {@code LIMIT 1}), so judging against it alone produced
 * false "rowCount below floor" and "ungrounded" failures.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "chat.eval", matches = "true")
@ActiveProfiles("eval")
@Import(EvalJudgeConfig.class)
class ChatEvalApplicationTest {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final int ATTEMPTS = 3;
    private static final int PASSES_REQUIRED = 2;

    @LocalServerPort
    int port;

    @Autowired
    KnowledgeGraphJudgeEvaluator kgJudgeEvaluator;

    @Autowired
    FactCheckingEvaluator factChecker;

    @Value("${eval.factcheck:false}")
    boolean factCheckEnabled;

    @Autowired
    Driver neo4jDriver;

    @Autowired
    OpenAiChatModel evalJudgeModel;

    private static final AtomicReference<Try<Void>> PREFLIGHT = new AtomicReference<>();

    ChatEvalClient client;

    @BeforeEach
    void setUp() {
        client = ChatEvalClient.at(port);
        PREFLIGHT.compareAndSet(null, Try.run(this::preflight));
        // Re-throw the same clear failure for every case instead of N raw downstream errors.
        PREFLIGHT.get().get();
    }

    /**
     * Fails once with a clear message when the environment is broken, so a bad key or missing graph
     * is not reported as N identical quality failures.
     */
    private void preflight() {
        val documentCount = Try.of(() -> neo4jDriver.executableQuery("MATCH (d:Document) RETURN count(d) AS n")
                        .execute().records().get(0).get("n").asLong())
                .getOrElseThrow(cause -> new IllegalStateException(
                        "Preflight: Neo4j (with APOC) unreachable at bolt://localhost:7687", cause));
        assertThat(documentCount).as("Preflight: graph must contain loaded :Document nodes").isPositive();
        val ping = Try.of(() -> evalJudgeModel.call("Reply with the single word: ok"))
                .getOrElseThrow(cause -> new IllegalStateException(
                        "Preflight: judge/chat LLM call failed (check OPENAI_API_KEY for app.models.chat.base-url): "
                                + cause.getMessage(), cause));
        assertThat(ping).as("Preflight: LLM must answer").isNotBlank();
    }

    static java.util.stream.Stream<GoldenCase> goldenCases() {
        return GoldenCases.all().stream();
    }

    @ParameterizedTest(name = "eval case [{index}]")
    @MethodSource("goldenCases")
    void runGoldenCase(GoldenCase goldenCase) {
        logEvalHeader(goldenCase);
        // The agent is non-deterministic, so a case passes on a majority of attempts. Attempts stop
        // as soon as the outcome is decided (2 passes, or too many failures to still reach 2).
        val outcomes = runAttempts(goldenCase, io.vavr.collection.List.empty());
        val passes = outcomes.count(Try::isSuccess);
        log.info("=== eval case result === {}/{} attempts passed (need {})", passes, outcomes.size(), PASSES_REQUIRED);
        assertThat(passes)
                .as("case must pass %d of %d attempts; failures: %s", PASSES_REQUIRED, ATTEMPTS,
                        outcomes.filter(Try::isFailure).map(attempt -> attempt.getCause().getMessage()).mkString(" || "))
                .isGreaterThanOrEqualTo(PASSES_REQUIRED);
    }

    private io.vavr.collection.List<Try<Void>> runAttempts(GoldenCase goldenCase, io.vavr.collection.List<Try<Void>> done) {
        val passes = done.count(Try::isSuccess);
        val failures = done.count(Try::isFailure);
        return passes >= PASSES_REQUIRED || failures > ATTEMPTS - PASSES_REQUIRED
                ? done
                : runAttempts(goldenCase, done.append(runAttempt(goldenCase, done.size())));
    }

    private Try<Void> runAttempt(GoldenCase goldenCase, int attempt) {
        log.info("=== attempt {}/{} ===", attempt + 1, ATTEMPTS);
        return Try.run(() -> {
            val responses = runConversation(goldenCase, UUID.randomUUID().toString());
            assertInvariants(goldenCase, responses);
            assertJudge(goldenCase, responses);
            logFactCheck(goldenCase, responses);
        }).onFailure(cause -> log.warn("attempt {} failed: {}", attempt + 1, cause.getMessage()));
    }

    private List<ChatResponse> runConversation(GoldenCase goldenCase, String conversationId) {
        if (goldenCase.followupPrompt() != null) {
            log.info("--- turn 1 ---");
        }
        val first = client.ask(goldenCase.prompt(), conversationId);
        logResponse(first);
        if (goldenCase.followupPrompt() == null) {
            return List.of(first);
        }
        log.info("--- turn 2 ---");
        val second = client.ask(goldenCase.followupPrompt(), conversationId);
        logResponse(second);
        return List.of(first, second);
    }

    private static void logEvalHeader(GoldenCase goldenCase) {
        val kind = goldenCase.groundednessProbe() ? "groundedness-probe" : "qa";
        val followup = goldenCase.followupPrompt() != null
                ? " | followup: " + goldenCase.followupPrompt()
                : "";
        log.info("=== eval case [{}] === prompt: {}{} | expected entities: {} | minRowCount: {}",
                kind, goldenCase.prompt(), followup, goldenCase.expectedEntities(),
                goldenCase.minRowCount());
    }

    private static void logResponse(ChatResponse response) {
        log.info("--- agent response --- answer: {} | rowCount: {} | skills: {} | truncated: {} | error: {}",
                response.answer(), response.rowCount(), response.skillsExecuted(),
                response.truncated(), response.error());
        log.info("--- cypher queries ({}) ---", response.cypherQueries().size());
        response.cypherQueries().forEach(cypher -> log.info("    {}", cypher));
    }

    private void assertInvariants(GoldenCase goldenCase, List<ChatResponse> responses) {
        val finalResponse = responses.get(responses.size() - 1);
        assertThat(finalResponse).as("chat response must be present").isNotNull();
        assertThat(responses).as("no turn may report an error")
                .allMatch(response -> response.error() == null);
        assertNoWriteCypher(responses);
        if (goldenCase.groundednessProbe()) {
            return;
        }
        assertThat(finalResponse.answer()).as("answer must not be blank").isNotBlank();
        assertThat(allCypherQueries(responses)).as("cypherQueries must be non-empty").isNotEmpty();
        assertSkillsExecuted(goldenCase, responses);
        assertRowCountFloor(goldenCase, responses);
        assertExpectedEntitiesMentioned(goldenCase, finalResponse);
    }

    private static void assertNoWriteCypher(List<ChatResponse> responses) {
        val writePattern = GraphTools.writePattern();
        assertThat(allCypherQueries(responses))
                .as("no cypher may contain a write operation")
                .noneMatch(cypher -> writePattern.matcher(cypher).find());
    }

    private static void assertSkillsExecuted(GoldenCase goldenCase, List<ChatResponse> responses) {
        // A follow-up turn legitimately skips the Skill tool when turn 1 already loaded the skill
        // document into the conversation, so skills are aggregated across turns.
        val allSkills = responses.stream()
                .flatMap(response -> response.skillsExecuted().stream())
                .toList();
        assertThat(allSkills).as("at least one skill executed across the conversation").isNotEmpty();
        if (!goldenCase.expectedSkills().isEmpty()) {
            assertThat(allSkills)
                    .as("must execute an expected skill")
                    .anyMatch(goldenCase.expectedSkills()::contains);
        }
    }

    private static void assertRowCountFloor(GoldenCase goldenCase, List<ChatResponse> responses) {
        // The floor checks the largest single query result, not the last: agents often end with a
        // small lookup (e.g. LIMIT 1 for an entity description) after the substantive query.
        val maxRowCount = responses.stream()
                .flatMap(response -> response.evidence().stream())
                .mapToInt(QueryEvidence::count)
                .max()
                .orElse(0);
        assertThat(maxRowCount)
                .as("largest retrieved row set meets floor")
                .isGreaterThanOrEqualTo(goldenCase.minRowCount());
    }

    private static void assertExpectedEntitiesMentioned(GoldenCase goldenCase, ChatResponse finalResponse) {
        val answerLower = finalResponse.answer().toLowerCase();
        val mentioned = goldenCase.expectedEntities().stream()
                .filter(entity -> answerLower.contains(entity.toLowerCase()))
                .toList();
        assertThat(mentioned)
                .as("answer mentions at least %d of expected entities %s", goldenCase.minExpectedMatches(),
                        goldenCase.expectedEntities())
                .hasSizeGreaterThanOrEqualTo(goldenCase.minExpectedMatches());
    }

    private static List<String> allCypherQueries(List<ChatResponse> responses) {
        return responses.stream()
                .flatMap(response -> response.cypherQueries().stream())
                .toList();
    }

    private void assertJudge(GoldenCase goldenCase, List<ChatResponse> responses) {
        val finalResponse = responses.get(responses.size() - 1);
        val question = goldenCase.followupPrompt() != null
                ? "Turn 1: " + goldenCase.prompt() + "\nTurn 2 (answer this one): " + goldenCase.followupPrompt()
                : goldenCase.prompt();
        val documents = io.vavr.collection.List.of(expectedFactsDocument(goldenCase))
                .appendAll(evidenceDocuments(responses))
                .asJava();
        val request = new EvaluationRequest(question, documents, finalResponse.answer());
        val result = goldenCase.groundednessProbe()
                ? kgJudgeEvaluator.evaluateProbe(request)
                : kgJudgeEvaluator.evaluate(request);
        val grounded = Boolean.TRUE.equals(result.getMetadata().get("grounded"));
        val correct = Boolean.TRUE.equals(result.getMetadata().get("correct"));
        if (goldenCase.groundednessProbe()) {
            assertThat(grounded)
                    .as("probe must not fabricate: " + result.getFeedback())
                    .isTrue();
            assertThat(correct)
                    .as("probe answer must address the question: " + result.getFeedback())
                    .isTrue();
        } else {
            assertThat(grounded)
                    .as("answer must be grounded: " + result.getFeedback())
                    .isTrue();
            assertThat(correct)
                    .as("answer must be correct: " + result.getFeedback())
                    .isTrue();
        }
    }

    /** Off unless {@code -Deval.factcheck=true}. Advisory only: the whole-answer-as-one-claim check is too brittle to gate on; the judge is the gate. */
    private void logFactCheck(GoldenCase goldenCase, List<ChatResponse> responses) {
        if (!factCheckEnabled || goldenCase.groundednessProbe()) {
            return;
        }
        val finalResponse = responses.get(responses.size() - 1);
        val request = new EvaluationRequest("", evidenceDocuments(responses), finalResponse.answer());
        Try.of(() -> TransportRetry.call("fact check LLM call", () -> factChecker.evaluate(request)))
                .onSuccess(result -> log.info("--- fact check (advisory) --- pass: {} | feedback: {}", result.isPass(),
                        result.getFeedback()))
                .onFailure(cause -> log.warn("fact check errored (advisory): {}", cause.getMessage()));
    }

    private static Document expectedFactsDocument(GoldenCase goldenCase) {
        return new Document("Expected facts: " + String.join(", ", goldenCase.expectedEntities()),
                java.util.Map.of("role", EvalJudge.EXPECTED_FACTS_ROLE));
    }

    /** One document per executed query so the judge sees the same rows the agent saw. */
    private static List<Document> evidenceDocuments(List<ChatResponse> responses) {
        return io.vavr.collection.List.ofAll(responses)
                .flatMap(response -> response.evidence())
                .zipWithIndex()
                .map(indexed -> evidenceDocument(indexed._2 + 1, indexed._1))
                .asJava();
    }

    private static Document evidenceDocument(int queryNumber, QueryEvidence evidence) {
        val outcome = evidence.error() != null
                ? "Query error: " + evidence.error()
                : "Rows (count: %d, truncated: %s):\n%s%s".formatted(evidence.count(), evidence.truncated(),
                        toJson(evidence.rows()), truncationNote(evidence));
        return new Document("Cypher query %d:\n%s\n%s".formatted(queryNumber, evidence.cypher(), outcome));
    }

    private static String truncationNote(QueryEvidence evidence) {
        return evidence.truncated()
                ? "\nRows were truncated to the visible subset; judge the claim only against the rows above.\n"
                : "";
    }

    private static String toJson(Object value) {
        return Try.of(() -> JSON.writeValueAsString(value))
                .getOrElse(() -> String.valueOf(value));
    }
}
