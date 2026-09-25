package com.minisparky.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String agentApiKey,
        String userTokenSecret,
        int cartTtlSeconds,
        int maxCartTotalPaise,
        int maxLinesPerCart,
        int agentMaxCallsPerMinute,
        boolean policyGuardDisabled) {}