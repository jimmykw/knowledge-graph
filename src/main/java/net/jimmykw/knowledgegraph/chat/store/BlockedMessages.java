package net.jimmykw.knowledgegraph.chat.store;

import java.util.Map;

import lombok.experimental.UtilityClass;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/** Marks messages of turns the intent gate blocked, so the classifier can leave them out of its context. */
@UtilityClass
public class BlockedMessages {

    public static final String KEY = "routeBlocked";

    public static Message user(String text) {
        return UserMessage.builder().text(text).metadata(Map.of(KEY, true)).build();
    }

    public static Message assistant(String text) {
        return AssistantMessage.builder().content(text).properties(Map.of(KEY, true)).build();
    }

    public static boolean isBlocked(Message message) {
        return Boolean.TRUE.equals(message.getMetadata().get(KEY));
    }
}
