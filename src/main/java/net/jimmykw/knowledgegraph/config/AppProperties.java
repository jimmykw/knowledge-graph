package net.jimmykw.knowledgegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(int maxPages, int chunkTokens, int poolSize, Chat chat) {

    public record Chat(int resultRowLimit, int maxPromptLength, int maxAttempts, long queryTimeoutMs) {
        public Chat {
            if (resultRowLimit <= 0) {
                resultRowLimit = 50;
            }
            if (maxPromptLength <= 0) {
                maxPromptLength = 2000;
            }
            if (maxAttempts <= 0) {
                maxAttempts = 3;
            }
            if (queryTimeoutMs <= 0) {
                queryTimeoutMs = 10000;
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
            chat = new Chat(0, 0, 0, 0L);
        }
    }
}
