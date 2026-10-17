package net.jimmykw.knowledgegraph.graph;

import io.vavr.collection.List;
import lombok.experimental.UtilityClass;
import lombok.val;

/** The labels the extractor is steered toward, best first. The same order picks the surviving label when two nodes merge. */
@UtilityClass
public class PreferredLabels {

    public static final List<String> RANKED = List.of(
            "Person", "Organization", "Location", "Event", "Legislation", "Award",
            "Publication", "Work", "Product", "Technology", "Concept");

    private static final String CATCH_ALL = "Concept";

    /** Lower is better. Preferred labels by position; custom labels just above Concept. */
    public static int rank(String label) {
        val position = RANKED.indexOf(label);
        val catchAll = RANKED.indexOf(CATCH_ALL);
        return position < 0 ? catchAll : position >= catchAll ? position + 1 : position;
    }

    /** The better-ranked of two labels; the current one wins a tie, so an existing node keeps its label unless the other is better. */
    public static String better(String current, String other) {
        return rank(other) < rank(current) ? other : current;
    }
}
