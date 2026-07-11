package net.jimmykw.knowledgegraph.api;

import java.util.Map;

public record GraphSummaryResponse(
        String documentId,
        String documentHash,
        String status,
        int nodeCount,
        int relationshipCount,
        Map<String, Integer> countsByLabel,
        Map<String, Integer> countsByType,
        int failedChunks,
        long processingTimeMs) {
}
