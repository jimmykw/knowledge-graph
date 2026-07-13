package net.jimmykw.knowledgegraph.chat;

import net.jimmykw.knowledgegraph.chat.CypherExecutor.ExecutedResults;
import net.jimmykw.knowledgegraph.config.AppProperties;
import net.jimmykw.knowledgegraph.exception.ChatException;
import net.jimmykw.knowledgegraph.exception.InvalidPromptException;
import net.jimmykw.knowledgegraph.exception.Neo4jUnavailableException;
import net.jimmykw.knowledgegraph.graph.SchemaSnapshot;

import io.vavr.control.Option;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final SchemaService schemaService;
    private final CypherGenerationService cypherGenerationService;
    private final CypherExecutor cypherExecutor;
    private final AnswerSynthesisService answerSynthesisService;
    private final AppProperties appProperties;

    public ChatResponse chat(String prompt) {
        validate(prompt);
        val schema = schemaService.readSchemaForChat();
        log.info("Chat: schema loaded — {} label(s), {} relationship type(s)",
                schema.labels().size(), schema.relTypes().size());

        val outcome = cypherLoop(schema, prompt);
        val answer = synthesisLoop(prompt, outcome.cypher(), outcome.executed());
        return new ChatResponse(answer, outcome.cypher(), outcome.executed().rows(),
                outcome.executed().truncated(), outcome.executed().count(), null);
    }

    private void validate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new InvalidPromptException("Prompt must not be blank.");
        }
        val maxLength = appProperties.chat().maxPromptLength();
        if (prompt.length() > maxLength) {
            throw new InvalidPromptException(
                    "Prompt exceeds the maximum length of " + maxLength + " characters.");
        }
    }

    private CypherLoopOutcome cypherLoop(SchemaSnapshot schema, String prompt) {
        val maxAttempts = appProperties.chat().maxAttempts();
        val attempts = io.vavr.collection.List.rangeClosed(1, maxAttempts);
        val initial = new CypherLoopState(Option.none(), null, null);
        val finalState = attempts.foldLeft(initial, (state, attempt) -> nextCypherAttempt(state, attempt, schema, prompt));
        return finalState.result()
                .map(executed -> new CypherLoopOutcome(finalState.lastCypher(), executed))
                .getOrElseThrow(() -> cypherExhausted(maxAttempts, finalState));
    }

    private CypherLoopState nextCypherAttempt(CypherLoopState state, int attempt,
                                              SchemaSnapshot schema, String prompt) {
        if (state.result().isDefined()) {
            return state;
        }
        log.info("Chat: Cypher generation attempt {}/{}", attempt, appProperties.chat().maxAttempts());
        val generated = cypherGenerationService.generate(schema, prompt, Option.of(state.lastError()));
        if (generated.isFailure()) {
            return new CypherLoopState(Option.none(), state.lastCypher(), errorMessage(generated.getCause()));
        }
        val cypher = generated.get().cypher();
        val executed = cypherExecutor.execute(cypher);
        if (executed.isFailure()) {
            val cause = executed.getCause();
            if (cause instanceof Neo4jUnavailableException unavailable) {
                throw unavailable;
            }
            return new CypherLoopState(Option.none(), cypher, errorMessage(cause));
        }
        return new CypherLoopState(Option.some(executed.get()), cypher, null);
    }

    private ChatException cypherExhausted(int maxAttempts, CypherLoopState state) {
        val error = "Could not generate valid Cypher after " + maxAttempts
                + " attempts: " + state.lastError();
        return new ChatException(error, state.lastCypher(), error);
    }

    private String synthesisLoop(String prompt, String cypher, ExecutedResults executed) {
        val maxAttempts = appProperties.chat().maxAttempts();
        val attempts = io.vavr.collection.List.rangeClosed(1, maxAttempts);
        val initial = new SynthesisState(Option.none(), null);
        val finalState = attempts.foldLeft(initial,
                (state, attempt) -> nextSynthesisAttempt(state, attempt, prompt, cypher, executed));
        return finalState.answer().getOrElseThrow(() -> synthesisExhausted(maxAttempts, finalState, cypher));
    }

    private SynthesisState nextSynthesisAttempt(SynthesisState state, int attempt,
                                                String prompt, String cypher, ExecutedResults executed) {
        if (state.answer().isDefined()) {
            return state;
        }
        log.info("Chat: answer synthesis attempt {}/{}", attempt, appProperties.chat().maxAttempts());
        val synthesized = answerSynthesisService.synthesize(prompt, cypher, executed, Option.of(state.lastError()));
        if (synthesized.isFailure()) {
            return new SynthesisState(Option.none(), errorMessage(synthesized.getCause()));
        }
        val answer = synthesized.get();
        if (answer == null || answer.isBlank()) {
            return new SynthesisState(Option.none(), "LLM returned an empty answer");
        }
        return new SynthesisState(Option.some(answer), null);
    }

    private ChatException synthesisExhausted(int maxAttempts, SynthesisState state, String cypher) {
        val error = "Could not synthesize an answer after " + maxAttempts
                + " attempts: " + state.lastError();
        return new ChatException(error, cypher, error);
    }

    private static String errorMessage(Throwable cause) {
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private record CypherLoopState(Option<ExecutedResults> result, String lastCypher, String lastError) {
    }

    private record CypherLoopOutcome(String cypher, ExecutedResults executed) {
    }

    private record SynthesisState(Option<String> answer, String lastError) {
    }
}
