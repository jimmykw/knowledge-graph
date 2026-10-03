package net.jimmykw.knowledgegraph.chat.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import io.vavr.collection.List;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.eval.GoldenCases;
import net.jimmykw.knowledgegraph.config.AppProperties.Routing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Real-API eval of the intent gate (OpenRouter System One, jev-1.13). Needs OPENAI_API_KEY (the OpenRouter key).
 * Hard gate: no graph question is ever BLOCKED. Reported: recall of blocks on non-graph prompts, false-block count.
 */
@Slf4j
@EnabledIfSystemProperty(named = "routing.eval", matches = "true")
class RoutingEvalTest {

    private record Labeled(String prompt, RouteIntent expected, List<Message> history) {
        Labeled(String prompt, RouteIntent expected) {
            this(prompt, expected, List.empty());
        }
    }

    private static final List<Message> IBM_HISTORY = List.of(new UserMessage("What did IBM create?"),
            new AssistantMessage("IBM created FORTRAN, System/360, SAGE, the Universal Product Code, OS/2 and the Selectric."));

    private static final List<Labeled> LABELED = List.of(
            new Labeled("What did IBM invent?", RouteIntent.GRAPH),
            new Labeled("Who founded Microsoft?", RouteIntent.GRAPH),
            new Labeled("How many entities are in the graph?", RouteIntent.GRAPH),
            new Labeled("Which document mentions the Apple II?", RouteIntent.GRAPH),
            new Labeled("which of those were collaborations with other companies?", RouteIntent.GRAPH, IBM_HISTORY),
            new Labeled("Hello!", RouteIntent.CHITCHAT),
            new Labeled("Thanks, that was helpful!", RouteIntent.CHITCHAT),
            new Labeled("Who are you?", RouteIntent.CHITCHAT),
            new Labeled("What can you do?", RouteIntent.CHITCHAT),
            new Labeled("Good morning", RouteIntent.CHITCHAT),
            new Labeled("What is the weather in Paris tomorrow?", RouteIntent.OFF_TOPIC),
            new Labeled("Write me a Python function to reverse a string.", RouteIntent.OFF_TOPIC),
            new Labeled("Who won the 2018 World Cup?", RouteIntent.OFF_TOPIC),
            new Labeled("Give me a recipe for pancakes.", RouteIntent.OFF_TOPIC),
            new Labeled("What is the capital of Australia?", RouteIntent.OFF_TOPIC),
            new Labeled("What is the current price of IBM stock?", RouteIntent.UNANSWERABLE),
            new Labeled("Will Microsoft be worth more next year?", RouteIntent.UNANSWERABLE),
            new Labeled("Delete all Apple nodes from the graph.", RouteIntent.UNANSWERABLE),
            new Labeled("What is 1234 times 5678?", RouteIntent.UNANSWERABLE),
            new Labeled("Which company is the best, in your opinion?", RouteIntent.UNANSWERABLE));

    @Test
    void graphQuestionsAreNeverBlocked() {
        val router = router();
        val golden = List.ofAll(GoldenCases.all());
        val labeledGraph = LABELED.filter(l -> l.expected() == RouteIntent.GRAPH);
        val results = labeledGraph.map(l -> run(router, l.prompt(), l.history()))
                .appendAll(golden.map(c -> run(router, c.prompt(), List.empty())))
                .appendAll(golden.filter(c -> c.followupPrompt() != null).map(c -> run(router, c.followupPrompt(), IBM_HISTORY)));
        assertThat(results.filter(d -> d.status() == RouteStatus.BLOCKED)).isEmpty();
        assertThat(results.filter(d -> d.status() == RouteStatus.SKIPPED)).as("classification failures").isEmpty();
    }

    @Test
    void reportsBlockRecallOnNonGraphPrompts() {
        val router = router();
        val nonGraph = LABELED.filter(l -> l.expected() != RouteIntent.GRAPH);
        val blocked = nonGraph.count(l -> run(router, l.prompt(), l.history()).status() == RouteStatus.BLOCKED);
        log.info("ROUTING EVAL: blocked {}/{} non-graph prompts (recall {}), false blocks on graph prompts asserted separately",
                blocked, nonGraph.size(), String.format("%.2f", blocked / (double) nonGraph.size()));
        assertThat(blocked).isPositive();
    }

    @Test
    void graphQuestionAfterBlockedTurnIsNotBlocked() {
        val router = router();
        val history = List.<Message>of(new UserMessage("Who won the 2018 World Cup?"),
                new AssistantMessage("France won the 2018 FIFA World Cup, beating Croatia 4-2."));
        val results = List.of("What did IBM create?", "What did IBM invent?", "Who founded Microsoft?")
                .map(p -> run(router, p, history));
        assertThat(results.filter(d -> d.status() == RouteStatus.BLOCKED)).isEmpty();
    }

    private static RouteDecision run(QuestionRouter router, String prompt, List<Message> history) {
        val decision = router.route(prompt, history).get();
        log.info("ROUTING EVAL: [{}] {} graphP={} <- \"{}\"", decision.status(), decision.intent(), decision.graphProbability(), prompt);
        return decision;
    }

    private static QuestionRouter router() {
        val key = System.getenv("OPENAI_API_KEY");
        assertThat(key).as("OPENAI_API_KEY (OpenRouter key) must be set").isNotBlank();
        val routing = new Routing(true, Double.parseDouble(System.getProperty("routing.threshold", "0.9")),
                Duration.ofSeconds(10), null, key, null);
        return new TypeSafeQuestionRouter(new RestSystemOneClient(routing, () -> List.of("HistoryOfIBM", "HistoryOfApple", "HisotryOfMicrosoft")), routing.blockThreshold());
    }
}
