package net.jimmykw.knowledgegraph.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import net.jimmykw.knowledgegraph.chat.eval.EvalJudge;
import net.jimmykw.knowledgegraph.chat.eval.EvalJudgeConfig;
import net.jimmykw.knowledgegraph.chat.eval.GoldenCase;
import net.jimmykw.knowledgegraph.chat.eval.GoldenCases;

import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "chat.eval", matches = "true")
@ActiveProfiles("eval")
@Import(EvalJudgeConfig.class)
class ChatEvalApplicationTest {

    @LocalServerPort
    int port;

    @Autowired
    EvalJudge judge;

    ChatEvalClient client;

    @BeforeEach
    void setUp() {
        client = ChatEvalClient.at(port);
    }

    static java.util.stream.Stream<GoldenCase> goldenCases() {
        return GoldenCases.all().stream();
    }

    @ParameterizedTest(name = "eval case [{index}]")
    @MethodSource("goldenCases")
    void runGoldenCase(GoldenCase goldenCase) {
        val conversationId = UUID.randomUUID().toString();
        logEvalHeader(goldenCase);
        val response = goldenCase.followupPrompt() == null
                ? client.ask(goldenCase.prompt(), conversationId)
                : runMultiTurn(goldenCase, conversationId);
        logResponse(response);
        assertInvariants(goldenCase, response);
        assertJudge(goldenCase, response);
    }

    private ChatResponse runMultiTurn(GoldenCase goldenCase, String conversationId) {
        log.info("--- turn 1 ---");
        logResponse(client.ask(goldenCase.prompt(), conversationId));
        log.info("--- turn 2 ---");
        return client.ask(goldenCase.followupPrompt(), conversationId);
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

    private void assertInvariants(GoldenCase goldenCase, ChatResponse response) {
        assertThat(response).as("chat response must be present").isNotNull();
        assertThat(response.error()).as("error must be null").isNull();
        if (goldenCase.groundednessProbe()) {
            return;
        }
        assertThat(response.answer()).as("answer must not be blank").isNotBlank();
        val writePattern = GraphTools.writePattern();
        assertThat(response.cypherQueries())
                .as("cypherQueries must be non-empty").isNotEmpty()
                .as("no cypher may contain a write operation")
                .noneMatch(cypher -> writePattern.matcher(cypher).find());
        assertThat(response.skillsExecuted()).as("at least one skill executed").isNotEmpty();
        if (!goldenCase.expectedSkills().isEmpty()) {
            assertThat(response.skillsExecuted())
                    .as("must execute an expected skill")
                    .anyMatch(goldenCase.expectedSkills()::contains);
        }
        assertThat(response.rowCount())
                .as("rowCount meets floor")
                .isGreaterThanOrEqualTo(goldenCase.minRowCount());
        val answerLower = response.answer().toLowerCase();
        assertThat(goldenCase.expectedEntities())
                .as("answer mentions at least one expected entity")
                .anyMatch(entity -> answerLower.contains(entity.toLowerCase()));
    }

    private void assertJudge(GoldenCase goldenCase, ChatResponse response) {
        val question = goldenCase.followupPrompt() != null
                ? goldenCase.followupPrompt()
                : goldenCase.prompt();
        val result = judge.score(question, goldenCase.expectedEntities(), response);
        log.info("--- judge score --- grounded: {} | correct: {} | rationale: {}",
                result.grounded(), result.correct(), result.rationale());
        if (goldenCase.groundednessProbe()) {
            assertThat(result.grounded())
                    .as("probe must not fabricate: " + result.rationale())
                    .isTrue();
        } else {
            assertThat(result.grounded())
                    .as("answer must be grounded: " + result.rationale())
                    .isTrue();
            assertThat(result.correct())
                    .as("answer must be correct: " + result.rationale())
                    .isTrue();
        }
    }
}
