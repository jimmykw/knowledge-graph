package net.jimmykw.knowledgegraph.chat.routing;

import java.util.Map;

/** Low-level System One call: one state plus named typed questions in, the {@code answers} map out. */
public interface SystemOneApi {

    /** @return the response's {@code answers} object, keyed by question name; throws on transport or shape errors */
    Map<String, Object> ask(String state, Map<String, Object> questions);
}
