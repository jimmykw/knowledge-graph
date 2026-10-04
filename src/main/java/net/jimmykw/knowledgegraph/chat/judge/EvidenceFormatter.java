package net.jimmykw.knowledgegraph.chat.judge;

import java.util.Map;

import io.vavr.collection.List;
import lombok.experimental.UtilityClass;
import lombok.val;
import net.jimmykw.knowledgegraph.chat.QueryEvidence;
import tools.jackson.databind.json.JsonMapper;

/** Renders the rows the agent retrieved into a bounded text block for the judge. */
@UtilityClass
public class EvidenceFormatter {

    public static final String NO_ROWS = "(no rows were retrieved)";

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final int MAX_CYPHER_CHARS = 300;

    /** @param truncated true when any row was dropped to fit the budget or the server already truncated a query result */
    public record Formatted(String text, boolean truncated) {
    }

    private record Block(String header, List<String> rows, boolean serverTruncated) {
        int fullLength() {
            return header.length() + rows.map(row -> row.length() + 1).sum().intValue();
        }
    }

    public static Formatted format(List<QueryEvidence> evidence, int maxChars) {
        val blocks = evidence.zipWithIndex().map(pair -> toBlock(pair._1, pair._2 + 1));
        val shares = shares(blocks, maxChars);
        val rendered = blocks.zip(shares).map(pair -> render(pair._1, pair._2));
        val text = rendered.map(Rendered::text).mkString("\n");
        val anyRows = evidence.exists(item -> item.rows() != null && !item.rows().isEmpty());
        val body = anyRows ? text : (text.isEmpty() ? NO_ROWS : text + "\n" + NO_ROWS);
        val truncated = rendered.exists(Rendered::dropped) || evidence.exists(QueryEvidence::truncated);
        return new Formatted(body, truncated);
    }

    private record Rendered(String text, boolean dropped) {
    }

    private static Block toBlock(QueryEvidence item, int number) {
        val cypher = item.cypher() == null ? "" : abbreviate(item.cypher().strip(), MAX_CYPHER_CHARS);
        val header = "[query " + number + "] " + cypher + (item.error() == null ? "" : "\nerror: " + item.error());
        val rows = item.rows() == null ? List.<String>empty() : List.ofAll(item.rows()).map(EvidenceFormatter::rowJson);
        return new Block(header, rows, item.truncated());
    }

    /** Max-min fair split: short queries keep what they need and the unused budget flows to the larger ones. */
    private static List<Integer> shares(List<Block> blocks, int maxChars) {
        val order = blocks.zipWithIndex().sortBy(pair -> pair._1.fullLength()).map(pair -> pair._2);
        val allocated = order.foldLeft(new Allocation(maxChars, blocks.size(), io.vavr.collection.HashMap.<Integer, Integer>empty()),
                (state, index) -> state.give(index, blocks.get(index).fullLength()));
        return List.range(0, blocks.size()).map(index -> allocated.byIndex.get(index).getOrElse(0));
    }

    private record Allocation(int remaining, int blocksLeft, io.vavr.collection.Map<Integer, Integer> byIndex) {
        Allocation give(int index, int need) {
            val share = Math.min(need, remaining / Math.max(blocksLeft, 1));
            return new Allocation(remaining - share, blocksLeft - 1, byIndex.put(index, share));
        }
    }

    private static Rendered render(Block block, int share) {
        val marker = "... %d more rows not shown";
        val budget = share - block.header.length();
        val kept = keepWithin(block.rows, Math.max(budget, 0), 0, List.empty());
        val dropped = block.rows.size() - kept.size();
        val lines = kept.prepend(block.header).appendAll(dropped > 0 ? List.of(marker.formatted(dropped)) : List.empty());
        return new Rendered(lines.mkString("\n"), dropped > 0);
    }

    private static List<String> keepWithin(List<String> rows, int budget, int used, List<String> kept) {
        if (rows.isEmpty() || used + rows.head().length() + 1 > budget) {
            return kept;
        }
        return keepWithin(rows.tail(), budget, used + rows.head().length() + 1, kept.append(rows.head()));
    }

    private static String rowJson(Map<String, Object> row) {
        return JSON.writeValueAsString(row);
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
