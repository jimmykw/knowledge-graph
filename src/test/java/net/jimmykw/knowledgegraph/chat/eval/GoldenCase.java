package net.jimmykw.knowledgegraph.chat.eval;

import java.util.List;

/**
 * @param expectedEntities    landmark facts a correct answer mentions; must not be words from the prompt itself
 * @param minExpectedMatches  how many of {@code expectedEntities} the answer must mention
 */
public record GoldenCase(String prompt, String followupPrompt, List<String> expectedSkills,
                         List<String> expectedEntities, int minExpectedMatches, int minRowCount,
                         boolean groundednessProbe) {
}
