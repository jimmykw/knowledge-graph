package net.jimmykw.knowledgegraph.graph;

import java.util.regex.Pattern;

import io.vavr.control.Option;
import lombok.experimental.UtilityClass;
import lombok.val;

/** Builds Lucene queries for the {@code entity_names} full-text index from entity names. */
@UtilityClass
public class FulltextQueries {

    private static final Pattern SPECIAL = Pattern.compile("([+\\-&|!(){}\\[\\]^\"~*?:\\\\/])");

    /**
     * The normalized name with every Lucene special character escaped. Lowercasing also defuses the AND / OR / NOT operators.
     * None when the name has no letter or digit, because such a query matches nothing (or fails to parse).
     */
    public static Option<String> forName(String name) {
        val normalized = EntityNormalizer.normalize(name);
        return normalized.codePoints().anyMatch(Character::isLetterOrDigit)
                ? Option.some(SPECIAL.matcher(normalized).replaceAll("\\\\$1"))
                : Option.none();
    }
}
