package net.jimmykw.knowledgegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(int maxPages, int chunkTokens, int poolSize, Chat chat) {

    public record Chat(int resultRowLimit, int maxPromptLength, int maxToolCallRounds, long queryTimeoutMs,
                       int historyWindow) {
        public Chat {
            if (resultRowLimit <= 0) {
                resultRowLimit = 50;
            }
            if (maxPromptLength <= 0) {
                maxPromptLength = 2000;
            }
            if (maxToolCallRounds <= 0) {
                maxToolCallRounds = 10;
            }
            if (queryTimeoutMs <= 0) {
                queryTimeoutMs = 10000;
            }
            if (historyWindow <= 0) {
                historyWindow = 20;
            }
        }
    }

    public AppProperties {
        if (maxPages <= 0) {
            maxPages = 50;
        }
        if (chunkTokens <= 0) {
            chunkTokens = 1000;
        }
        if (poolSize <= 0) {
            poolSize = 4;
        }
        if (chat == null) {
            chat = new Chat(0, 0, 0, 0L, 0);
        }
    }
}