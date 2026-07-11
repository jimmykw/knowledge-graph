package net.jimmykw.knowledgegraph.graph;

import java.util.Locale;

import lombok.experimental.UtilityClass;

@UtilityClass
public class EntityNormalizer {

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
