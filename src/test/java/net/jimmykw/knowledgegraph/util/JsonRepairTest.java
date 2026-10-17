package net.jimmykw.knowledgegraph.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.val;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JsonRepairTest {

    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    void nullAndBlankPassThrough() {
        assertThat(JsonRepair.repair(null)).isNull();
        assertThat(JsonRepair.repair("")).isEmpty();
        assertThat(JsonRepair.repair("   ")).isEqualTo("   ");
    }

    @Test
    void validJsonIsUnchanged() {
        val valid = "{\"entities\": [{\"name\": \"IBM\", \"label\": \"Organization\"}], \"relationships\": []}";
        assertThat(JsonRepair.repair(valid)).isEqualTo(valid);
    }

    @Test
    void stripsPlainAndJsonFences() {
        assertThat(JsonRepair.repair("```json\n{\"a\": 1}\n```")).isEqualTo("{\"a\": 1}\n");
        assertThat(JsonRepair.repair("```\n{\"a\": 1}\n```")).isEqualTo("{\"a\": 1}\n");
    }

    @Test
    void quotesUnquotedStringValue() {
        val repaired = JsonRepair.repair("{\"name\": IBM\"}");
        assertThat(JSON.readTree(repaired).get("name").asString()).isEqualTo("IBM");
    }

    @Test
    void removesTrailingCommas() {
        val repaired = JsonRepair.repair("{\"a\": [1, 2,], \"b\": 3,}");
        val tree = JSON.readTree(repaired);
        assertThat(tree.get("a")).hasSize(2);
        assertThat(tree.get("b").asInt()).isEqualTo(3);
    }

    @Test
    void repairsFencedTrailingCommaOutput() {
        val repaired = JsonRepair.repair("```json\n{\"entities\": [{\"name\": \"IBM\",}],}\n```");
        assertThat(JSON.readTree(repaired).get("entities")).hasSize(1);
    }

    @Test
    void documentedLimitTruncatedJsonStaysUnparseable() {
        val repaired = JsonRepair.repair("{\"entities\": [{\"name\": \"IBM\"");
        assertThatThrownBy(() -> JSON.readTree(repaired)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void documentedLimitProseIsNotTurnedIntoJson() {
        val repaired = JsonRepair.repair("Sure! Here are the entities.");
        assertThatThrownBy(() -> JSON.readTree(repaired)).isInstanceOf(RuntimeException.class);
    }
}
