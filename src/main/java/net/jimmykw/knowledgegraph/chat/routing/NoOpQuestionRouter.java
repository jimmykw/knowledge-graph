package net.jimmykw.knowledgegraph.chat.routing;

import io.vavr.collection.List;
import io.vavr.control.Option;
import org.springframework.ai.chat.messages.Message;

public class NoOpQuestionRouter implements QuestionRouter {

    @Override
    public Option<RouteDecision> route(String prompt, List<Message> history) {
        return Option.none();
    }
}
