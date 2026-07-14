package net.jimmykw.knowledgegraph.chat;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String answer,
        String cypher,
        List<Map<String, Object>> results,
        boolean truncated,
        int rowCount,
        String error,
        List<String> skillsExecuted,
        List<String> cypherQueries) {
}
