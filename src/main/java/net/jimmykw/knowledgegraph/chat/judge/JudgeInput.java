package net.jimmykw.knowledgegraph.chat.judge;

import io.vavr.collection.List;
import net.jimmykw.knowledgegraph.chat.QueryEvidence;
import org.springframework.ai.chat.messages.Message;

/** What the judge sees: stored history before this turn, the question, every retrieved query result, and the answer. */
public record JudgeInput(String question, List<Message> history, List<QueryEvidence> evidence, String answer) {
}
