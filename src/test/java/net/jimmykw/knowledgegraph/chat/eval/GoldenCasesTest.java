package net.jimmykw.knowledgegraph.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoldenCasesTest {

    @Test
    void allReturnsSixCasesWithNonBlankPrompts() {
        assertThat(GoldenCases.all())
                .hasSize(6)
                .allSatisfy(goldenCase -> assertThat(goldenCase.prompt()).isNotBlank());
    }

    @Test
    void exactlyOneGroundednessProbe() {
        assertThat(GoldenCases.all())
                .filteredOn(GoldenCase::groundednessProbe)
                .hasSize(1);
    }

    @Test
    void oneMultiTurnCaseWithCollaborationsFollowup() {
        assertThat(GoldenCases.all())
                .filteredOn(goldenCase -> goldenCase.followupPrompt() != null)
                .singleElement()
                .extracting(GoldenCase::followupPrompt)
                .asString()
                .contains("collaborations");
    }
}
