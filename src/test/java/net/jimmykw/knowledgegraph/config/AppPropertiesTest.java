package net.jimmykw.knowledgegraph.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import lombok.val;
import org.junit.jupiter.api.Test;

class AppPropertiesTest {

    @Test
    void zeroAndNullValuesFallBackToDefaults() {
        val props = new AppProperties(0, 0, null, null, null, null, null);

        assertThat(props.maxPages()).isEqualTo(50);
        assertThat(props.chunkTokens()).isEqualTo(1000);
        assertThat(props.chat().resultRowLimit()).isEqualTo(50);
        assertThat(props.chat().maxPromptLength()).isEqualTo(2000);
        assertThat(props.chat().maxToolCallRounds()).isEqualTo(10);
        assertThat(props.chat().queryTimeoutMs()).isEqualTo(10000L);
        assertThat(props.chat().historyWindow()).isEqualTo(20);
        assertThat(props.routing().enabled()).isFalse();
        assertThat(props.routing().blockThreshold()).isEqualTo(0.9);
        assertThat(props.judge().enabled()).isFalse();
        assertThat(props.judge().maxEvidenceChars()).isEqualTo(8000);
    }

    @Test
    void modelDefaults() {
        val models = new AppProperties(0, 0, null, null, null, null, null).models();

        assertThat(models.extract().temperature()).isEqualTo(0.2);
        assertThat(models.extract().timeout()).isEqualTo(Duration.ofSeconds(600));
        assertThat(models.extract().maxRetries()).isEqualTo(5);
        assertThat(models.extract().poolSize()).isEqualTo(4);
        assertThat(models.chat().temperature()).isEqualTo(0.2);
        assertThat(models.chat().timeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(models.chat().maxRetries()).isEqualTo(5);
    }

    @Test
    void nonPositivePoolSizeFallsBackToDefault() {
        val extract = new AppProperties.Models.Extract(null, null, null, null, null, null, -3);
        assertThat(extract.poolSize()).isEqualTo(4);
    }

    @Test
    void explicitValuesAreKept() {
        val chat = new AppProperties.Chat(5, 6, 7, 8L, 9);
        val models = new AppProperties.Models(
                new AppProperties.Models.Extract("u", "k", "m", 0.5, Duration.ofSeconds(1), 0, 2), null);
        val props = new AppProperties(3, 4, chat, models, new AppProperties.Routing(true, 0.5), new AppProperties.Judge(true, 10), null);

        assertThat(props.maxPages()).isEqualTo(3);
        assertThat(props.chunkTokens()).isEqualTo(4);
        assertThat(props.chat()).isEqualTo(chat);
        assertThat(props.models().extract().maxRetries()).isZero();
        assertThat(props.models().extract().poolSize()).isEqualTo(2);
        assertThat(props.routing().blockThreshold()).isEqualTo(0.5);
        assertThat(props.judge().maxEvidenceChars()).isEqualTo(10);
    }
}
