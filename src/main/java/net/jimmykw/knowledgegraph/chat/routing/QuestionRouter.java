package net.jimmykw.knowledgegraph.chat.routing;

import io.vavr.collection.List;
import io.vavr.control.Option;
import org.springframework.ai.chat.messages.Message;

public interface QuestionRouter {

    /** @return none when routing is disabled; a SKIPPED decision when it is enabled but could not classify */
    Option<RouteDecision> route(String prompt, List<Message> history);
}
