package net.jimmykw.knowledgegraph.graph;

import java.util.Locale;
import java.util.regex.Pattern;

import io.vavr.collection.HashSet;
import io.vavr.collection.List;
import io.vavr.collection.Set;
import lombok.experimental.UtilityClass;
import lombok.val;

@UtilityClass
public class EntityNormalizer {

    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}\\p{IsPunctuation}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Set<String> CORPORATE_SUFFIXES =
            HashSet.of("inc", "incorporated", "corp", "corporation", "ltd", "limited", "co", "llc", "plc");

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Normalized name without punctuation, a leading "the" or trailing corporate suffixes ("Apple Inc." and "Apple" share
     * the key {@code apple}). Never empty when the normalized name is not: a name that strips to nothing keeps its normalized form.
     */
    public static String matchKey(String name) {
        val normalized = normalize(name);
        val words = List.of(WHITESPACE.split(PUNCTUATION.matcher(normalized).replaceAll(" ").trim())).filter(word -> !word.isEmpty());
        val stripped = stripSuffixes(words.headOption().contains("the") ? words.tail() : words);
        return stripped.isEmpty() ? normalized : stripped.mkString(" ");
    }

    private static List<String> stripSuffixes(List<String> words) {
        return words.nonEmpty() && CORPORATE_SUFFIXES.contains(words.last()) ? stripSuffixes(words.init()) : words;
    }
}
