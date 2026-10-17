package net.jimmykw.knowledgegraph.graph;

import lombok.experimental.UtilityClass;

/**
 * APOC builds its Cypher from the label / relationship type it is given and wraps it in backticks without escaping, so a
 * backtick (or a control character such as a newline) in an LLM-emitted label would break out of the identifier.
 */
@UtilityClass
public class CypherIdentifiers {

    public static boolean isSafe(String identifier) {
        return identifier != null && !identifier.isBlank() && identifier.chars().noneMatch(CypherIdentifiers::isForbidden);
    }

    private static boolean isForbidden(int codePoint) {
        return codePoint == '`' || Character.isISOControl(codePoint);
    }
}
