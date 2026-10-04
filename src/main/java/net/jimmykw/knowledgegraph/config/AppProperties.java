package net.jimmykw.knowledgegraph.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(int maxPages, int chunkTokens, Chat chat, Models models, Routing routing, Judge judge) {

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

    public record Routing(boolean enabled, double blockThreshold, Duration timeout, String baseUrl, String apiKey,
                          String model) {
        public Routing {
            if (blockThreshold <= 0) {
                blockThreshold = 0.9;
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(5);
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://openrouter.ai/api/v1";
            }
            if (model == null || model.isBlank()) {
                model = "jev-1.13";
            }
        }
    }

    public record Judge(boolean enabled, double minScore, Duration timeout, String baseUrl, String apiKey, String model,
                        int maxEvidenceChars) {
        public Judge {
            if (minScore <= 0) {
                minScore = 0.7;
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(3);
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://openrouter.ai/api/v1";
            }
            if (model == null || model.isBlank()) {
                model = "jev-1.13";
            }
            if (maxEvidenceChars <= 0) {
                maxEvidenceChars = 8000;
            }
        }
    }

    public record Models(Extract extract, ChatModel chat) {
        public Models {
            if (extract == null) { extract = new Extract(null, null, null, null, null, null, null); }
            if (chat == null) { chat = new ChatModel(null, null, null, null, null, null); }
        }

        public record Extract(String baseUrl, String apiKey, String model, Double temperature, Duration timeout,
                               Integer maxRetries, Integer poolSize) {
            public Extract {
                if (temperature == null) {
                    temperature = 0.2;
                }
                if (timeout == null) {
                    timeout = Duration.ofSeconds(600);
                }
                if (maxRetries == null) {
                    maxRetries = 5;
                }
                if (poolSize == null || poolSize <= 0) {
                    poolSize = 4;
                }
            }
        }

        public record ChatModel(String baseUrl, String apiKey, String model, Double temperature, Duration timeout,
                                 Integer maxRetries) {
            public ChatModel {
                if (temperature == null) {
                    temperature = 0.2;
                }
                if (timeout == null) {
                    timeout = Duration.ofSeconds(120);
                }
                if (maxRetries == null) {
                    maxRetries = 5;
                }
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
        if (chat == null) {
            chat = new Chat(0, 0, 0, 0L, 0);
        }
        if (models == null) { models = new Models(null, null); }
        if (routing == null) { routing = new Routing(false, 0, null, null, null, null); }
        if (judge == null) { judge = new Judge(false, 0, null, null, null, null, 0); }
    }
}