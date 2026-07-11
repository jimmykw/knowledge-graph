package net.jimmykw.knowledgegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(int maxPages, int chunkTokens, int poolSize) {

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
    }
}
