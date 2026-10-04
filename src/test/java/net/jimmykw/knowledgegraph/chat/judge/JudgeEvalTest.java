package net.jimmykw.knowledgegraph.chat.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import io.vavr.collection.List;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.QueryEvidence;
import net.jimmykw.knowledgegraph.chat.routing.RestSystemOneApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Real-API eval of the answer judge (OpenRouter System One, jev-1.13). Needs OPENAI_API_KEY (the OpenRouter key).
 * Hard gate: the six golden-case answers, built from fixed rows, are never LOW and never SKIPPED. Reported: score
 * distribution for good vs bad answers, the separation between them, and block recall at the threshold.
 */
@Slf4j
@EnabledIfSystemProperty(named = "judge.eval", matches = "true")
class JudgeEvalTest {

    private record Case(String name, String question, List<Message> history, List<QueryEvidence> evidence, String answer,
                        boolean good, boolean golden) {
    }

    private static QueryEvidence rows(String cypher, String... names) {
        val data = List.of(names).map(name -> Map.<String, Object>of("name", name)).toJavaList();
        return new QueryEvidence(cypher, data, data.size(), false, null);
    }

    /** Multi-column rows, as the agent's real queries return them (relationship and description columns, not just names). */
    @SafeVarargs
    private static QueryEvidence facts(String cypher, Map<String, Object>... data) {
        return new QueryEvidence(cypher, java.util.List.of(data), data.length, false, null);
    }

    private static final String CREATED = "MATCH (:Organization {name:'IBM'})-[:CREATED]->(x) RETURN x.name AS name";
    private static final List<QueryEvidence> IBM_CREATED = List.of(
            rows(CREATED, "FORTRAN", "System/360", "SAGE", "Universal Product Code", "OS/2", "Selectric"));
    private static final List<Message> IBM_HISTORY = List.of(new UserMessage("What did IBM create?"),
            new AssistantMessage("IBM created FORTRAN, System/360, SAGE, the Universal Product Code, OS/2, the Selectric, NSFNet and Prodigy."));
    private static final List<QueryEvidence> NOTHING = List.empty();

    private static Case good(String name, String question, List<Message> history, List<QueryEvidence> evidence, String answer) {
        return new Case(name, question, history, evidence, answer, true, false);
    }

    private static Case golden(String name, String question, List<Message> history, List<QueryEvidence> evidence, String answer) {
        return new Case(name, question, history, evidence, answer, true, true);
    }

    private static Case bad(String name, String question, List<Message> history, List<QueryEvidence> evidence, String answer) {
        return new Case(name, question, history, evidence, answer, false, false);
    }

    private static final List<Case> CASES = List.of(
            golden("golden: IBM create", "What did IBM create?", List.empty(), IBM_CREATED,
                    "IBM created FORTRAN, System/360, SAGE, the Universal Product Code, OS/2 and the Selectric typewriter."),
            golden("golden: IBM invent", "What did IBM invent?", List.empty(),
                    List.of(rows("MATCH (:Organization {name:'IBM'})-[:INVENTED]->(x) RETURN x.name AS name", "FORTRAN",
                            "magnetic stripe", "RISC architecture", "UPC")),
                    "According to the graph IBM invented FORTRAN, the magnetic stripe, RISC architecture and the UPC."),
            golden("golden: Microsoft and OS/2", "Tell me how Microsoft affected OS/2.", List.empty(),
                    List.of(facts("MATCH (a:Organization {name:'Microsoft'})-[r]-(b) WHERE b.name IN ['IBM','OS/2'] RETURN a.name AS source, type(r) AS rel, b.name AS target, r.description AS description",
                            Map.of("source", "Microsoft", "rel", "COLLABORATED_WITH", "target", "IBM",
                                    "description", "Microsoft and IBM jointly developed OS/2"),
                            Map.of("source", "Microsoft", "rel", "COMPETED_WITH", "target", "OS/2",
                                    "description", "Microsoft's Windows product competed with OS/2"))),
                    "Microsoft collaborated with IBM to develop OS/2, and its Windows product later competed with OS/2."),
            golden("golden: hard disks", "Tell me any information about hard disks.", List.empty(),
                    List.of(facts("MATCH (n) WHERE toLower(n.description) CONTAINS 'hard disk' RETURN n.name AS name, n.description AS description",
                            Map.of("name", "RAMAC", "description", "IBM 305 RAMAC, the first commercial hard disk drive"),
                            Map.of("name", "Seagate", "description", "Company that makes hard disk drives"))),
                    "The graph mentions the RAMAC, the first commercial hard disk drive, and Seagate, a company that makes hard disk drives."),
            golden("golden: collaborations follow-up", "which of those were collaborations with other companies?", IBM_HISTORY,
                    List.of(facts("MATCH (p {name:'IBM'})-[r:COLLABORATED_WITH]->(c) RETURN c.name AS partner, r.description AS project",
                            Map.of("partner", "University of Michigan", "project", "NSFNet"),
                            Map.of("partner", "MCI", "project", "NSFNet"),
                            Map.of("partner", "Sears", "project", "Prodigy"))),
                    "NSFNet, built with the University of Michigan and MCI, and Prodigy, built with Sears, were collaborations."),
            golden("golden: false premise", "When did IBM acquire Google?", List.empty(),
                    List.of(rows("MATCH (:Organization {name:'IBM'})-[:ACQUIRED]->(:Organization {name:'Google'}) RETURN 1 AS name")),
                    "The graph has no record of IBM acquiring Google, so I cannot give a date."),
            good("count", "How many people are in the graph?", List.empty(),
                    List.of(new QueryEvidence("MATCH (p:Person) RETURN count(p) AS people",
                            java.util.List.of(Map.<String, Object>of("people", 698)), 1, false, null)),
                    "There are 698 people in the graph."),
            good("honest empty", "Who founded Zorbotron Systems?", List.empty(), NOTHING,
                    "I could not find anything about Zorbotron Systems in the graph."),
            good("subset answer", "What did IBM create?", List.empty(), IBM_CREATED,
                    "IBM created FORTRAN and System/360, among other things."),
            good("source attribution", "Which document mentions the Apple II?", List.empty(),
                    List.of(rows("MATCH (n {name:'Apple II'}) RETURN n.source_doc AS name", "HistoryOfApple")),
                    "The Apple II is mentioned in HistoryOfApple."),
            bad("fabricated additions", "What did IBM create?", List.empty(), IBM_CREATED,
                    "IBM created FORTRAN, System/360, SAGE, the iPhone, Windows 95 and the Tesla Model S."),
            bad("one fabricated fact", "What did IBM invent?", List.empty(),
                    List.of(rows("MATCH (:Organization {name:'IBM'})-[:INVENTED]->(x) RETURN x.name AS name", "FORTRAN", "RISC architecture")),
                    "IBM invented FORTRAN, RISC architecture and the World Wide Web."),
            bad("wrong number", "How many people are in the graph?", List.empty(),
                    List.of(new QueryEvidence("MATCH (p:Person) RETURN count(p) AS people",
                            java.util.List.of(Map.<String, Object>of("people", 698)), 1, false, null)),
                    "There are 900 people in the graph."),
            bad("off question 1", "What did IBM create?", List.empty(), IBM_CREATED,
                    "Microsoft was founded by Bill Gates and Paul Allen in 1975."),
            bad("off question 2", "Which document mentions the Apple II?", List.empty(),
                    List.of(rows("MATCH (n {name:'Apple II'}) RETURN n.source_doc AS name", "HistoryOfApple")),
                    "FORTRAN was developed at IBM in the 1950s."),
            bad("off question follow-up", "which of those were collaborations with other companies?", IBM_HISTORY,
                    List.of(rows("MATCH (:Organization {name:'IBM'})-[:COLLABORATED_WITH]->(c) RETURN c.name AS name", "MCI")),
                    "The weather in Armonk is usually mild in spring."),
            bad("facts from no rows 1", "What did IBM create?", List.empty(), NOTHING,
                    "IBM created FORTRAN, System/360 and the personal computer."),
            bad("facts from no rows 2 (probe)", "When did IBM acquire Google?", List.empty(), NOTHING,
                    "IBM acquired Google in 2015 for $20 billion."),
            bad("wrong entity", "Who founded Microsoft?", List.empty(),
                    List.of(rows("MATCH (p:Person)-[:FOUNDED]->(:Organization {name:'Microsoft'}) RETURN p.name AS name", "Bill Gates",
                            "Paul Allen")),
                    "Microsoft was founded by Steve Jobs and Steve Wozniak."),
            bad("contradicts rows", "Which document mentions the Apple II?", List.empty(),
                    List.of(rows("MATCH (n {name:'Apple II'}) RETURN n.source_doc AS name", "HistoryOfApple")),
                    "No document mentions the Apple II."));

    private record Scored(Case item, AnswerQuality quality) {
        double combined() {
            return Math.min(quality.grounded(), quality.relevance());
        }
    }

    @Test
    void goldenAnswersAreNeverLowAndScoresSeparateGoodFromBad() {
        val judge = judge();
        val scored = CASES.map(item -> new Scored(item, judge.judge(new JudgeInput(item.question(), item.history(), item.evidence(),
                item.answer())).get()));
        scored.forEach(result -> log.info("JUDGE EVAL: [{}] {} grounded={} relevance={} <- {}", result.quality().status(),
                result.item().good() ? "GOOD" : "BAD ", fmt(result.quality().grounded()), fmt(result.quality().relevance()), result.item().name()));
        val skipped = scored.filter(result -> result.quality().status() == QualityStatus.SKIPPED);
        assertThat(skipped.map(result -> result.item().name())).as("judge calls that failed").isEmpty();
        report(scored);
        val goldenLow = scored.filter(result -> result.item().golden() && result.quality().status() == QualityStatus.LOW);
        assertThat(goldenLow.map(result -> result.item().name())).as("known-good golden answers flagged LOW").isEmpty();
    }

    private static void report(List<Scored> scored) {
        val good = scored.filter(result -> result.item().good());
        val bad = scored.reject(result -> result.item().good());
        val minGood = good.map(Scored::combined).min().getOrElse(0.0);
        val maxBad = bad.map(Scored::combined).max().getOrElse(1.0);
        val falseLow = good.count(result -> result.quality().status() == QualityStatus.LOW);
        val caught = bad.count(result -> result.quality().status() == QualityStatus.LOW);
        log.info("JUDGE EVAL: good mean={} min={} | bad mean={} max={} | separation (min good - max bad)={}",
                fmt(good.map(Scored::combined).average().getOrElse(0.0)), fmt(minGood),
                fmt(bad.map(Scored::combined).average().getOrElse(0.0)), fmt(maxBad), fmt(minGood - maxBad));
        log.info("JUDGE EVAL: threshold={} false LOW on good: {}/{} | bad answers flagged LOW: {}/{}", System.getProperty("judge.threshold", "0.7"),
                falseLow, good.size(), caught, bad.size());
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    private static AnswerJudge judge() {
        val key = System.getenv("OPENAI_API_KEY");
        assertThat(key).as("OPENAI_API_KEY (OpenRouter key) must be set").isNotBlank();
        val api = new RestSystemOneApi("https://openrouter.ai/api/v1", key, "jev-1.13", Duration.ofSeconds(10), "Judge");
        return new JevAnswerJudge(api, Double.parseDouble(System.getProperty("judge.threshold", "0.7")), 8000);
    }
}
