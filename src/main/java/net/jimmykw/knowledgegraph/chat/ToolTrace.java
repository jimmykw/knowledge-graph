package net.jimmykw.knowledgegraph.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ToolTrace {

    private final List<ToolCallEntry> toolCalls = new ArrayList<>();
    private final List<String> skillsExecuted = new ArrayList<>();

    private String lastCypher;
    private List<Map<String, Object>> lastRows = List.of();
    private int lastCount;
    private boolean lastTruncated;

    private String error;
    private int roundCount;
    private boolean maxRoundsExceeded;

    public record ToolCallEntry(String name, String arguments, String response, String error) {
    }

    public void recordToolCall(String name, String arguments, String response, String error) {
        toolCalls.add(new ToolCallEntry(name, arguments, response, error));
    }

    public void recordSkill(String skillName) {
        skillsExecuted.add(skillName);
    }

    public void recordRunReadCypher(String cypher, List<Map<String, Object>> rows, int count, boolean truncated) {
        lastCypher = cypher;
        lastRows = rows;
        lastCount = count;
        lastTruncated = truncated;
    }

    public void setError(String error) {
        if (this.error == null) {
            this.error = error;
        }
    }

    public void incrementRoundCount() {
        roundCount++;
    }

    public void markMaxRoundsExceeded() {
        maxRoundsExceeded = true;
    }

    public List<ToolCallEntry> toolCalls() {
        return List.copyOf(toolCalls);
    }

    public List<String> skillsExecuted() {
        return List.copyOf(skillsExecuted);
    }

    public String lastCypher() {
        return lastCypher;
    }

    public List<Map<String, Object>> lastRows() {
        return lastRows;
    }

    public int lastCount() {
        return lastCount;
    }

    public boolean lastTruncated() {
        return lastTruncated;
    }

    public String error() {
        return error;
    }

    public int roundCount() {
        return roundCount;
    }

    public boolean maxRoundsExceeded() {
        return maxRoundsExceeded;
    }
}
