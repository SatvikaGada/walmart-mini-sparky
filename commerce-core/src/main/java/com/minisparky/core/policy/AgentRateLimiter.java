package com.minisparky.core.policy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.minisparky.core.config.AppProperties;

@Component
public class AgentRateLimiter {

    private final int maxPerMinute;
    private final Map<String, Deque<Long>> calls = new ConcurrentHashMap<>();

    public AgentRateLimiter(AppProperties props) {
        this.maxPerMinute = props.agentMaxCallsPerMinute();
    }

    public boolean tryAcquire(String sessionId) {
        long now = System.currentTimeMillis();
        Deque<Long> window = calls.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) {
                window.pollFirst();
            }
            if (window.size() >= maxPerMinute) return false;
            window.addLast(now);
            return true;
        }
    }
}