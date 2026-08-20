package net.jimmykw.knowledgegraph.chat.eval;

import java.util.List;

public record GoldenCase(String prompt, String followupPrompt, List<String> expectedSkills,
                         List<String> expectedEntities, int minRowCount, boolean groundednessProbe) {
}
