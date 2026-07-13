package net.jimmykw.knowledgegraph.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class JsonRepair {

    public static String repair(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        var repaired = raw;
        if (repaired.contains("```")) {
            repaired = repaired.replaceAll("(?s)```(?:json)?\\s*", "");
        }
        repaired = repaired.replaceAll("(\"[^\"]+\"\\s*:\\s*)([A-Za-z][^\"]*\")", "$1 \"$2");
        repaired = repaired.replaceAll(",\\s*([}\\]])", "$1");
        return repaired;
    }
}
