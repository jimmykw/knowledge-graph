package net.jimmykw.knowledgegraph.chat;

import java.util.List;
import java.util.Map;

/**
 * The rows returned by one {@code runReadCypher} tool call. The chat agent typically runs several
 * queries per answer; the eval must judge against all of them, not just the last one.
 */
public record QueryEvidence(String cypher, List<Map<String, Object>> rows, int count, boolean truncated, String error) {
}
