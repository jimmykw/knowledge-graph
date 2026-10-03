package net.jimmykw.knowledgegraph.chat;

import java.util.List;
import java.util.Map;

import net.jimmykw.knowledgegraph.chat.routing.RouteDecision;

public record ChatResponse(
        String answer,
        String cypher,
        List<Map<String, Object>> results,
        boolean truncated,
        int rowCount,
        String error,
        List<String> skillsExecuted,
        List<String> cypherQueries,
        String conversationId,
        List<QueryEvidence> evidence,
        RouteDecision route) {
}