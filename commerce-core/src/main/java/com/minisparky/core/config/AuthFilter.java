package com.minisparky.core.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.minisparky.core.audit.AuditService;
import com.minisparky.core.auth.Caller;
import com.minisparky.core.auth.SessionService;
import com.minisparky.core.policy.AgentRateLimiter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final AppProperties props;
    private final SessionService sessions;
    private final AgentRateLimiter limiter;
    private final AuditService audit;

    public AuthFilter(AppProperties props, SessionService sessions,
                      AgentRateLimiter limiter, AuditService audit) {
        this.props = props;
        this.sessions = sessions;
        this.limiter = limiter;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/session");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        boolean userOnly = path.endsWith("/checkout") || path.endsWith("/cancel") || path.startsWith("/api/audit");

        Optional<String> userSession = sessions.verify(req.getHeader("X-User-Token"));
        if (userSession.isPresent()) {
            req.setAttribute("caller", new Caller("user", userSession.get()));
            chain.doFilter(req, res);
            return;
        }

        String agentKey = req.getHeader("X-Agent-Key");
        if (agentKey == null || !MessageDigest.isEqual(agentKey.getBytes(UTF_8), props.agentApiKey().getBytes(UTF_8))) {
            reject(res, 401, "UNAUTHENTICATED", "Missing or invalid credentials");
            return;
        }
        String sessionId = req.getHeader("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            reject(res, 400, "MISSING_SESSION", "X-Session-Id header is required for agent calls");
            return;
        }
        if (userOnly) {
            audit.record(sessionId, "agent", actionFor(path), null, null, null, "BLOCKED", "FORBIDDEN");
            reject(res, 403, "FORBIDDEN", "The agent principal cannot call this endpoint");
            return;
        }
        if (!limiter.tryAcquire(sessionId)) {
            audit.record(sessionId, "agent", "RATE_LIMIT", null, null, null, "BLOCKED", "RATE_LIMITED");
            reject(res, 429, "RATE_LIMITED", "Too many agent calls this minute");
            return;
        }
        req.setAttribute("caller", new Caller("agent", sessionId));
        chain.doFilter(req, res);
    }

    private static String actionFor(String path) {
        if (path.endsWith("/checkout")) return "CHECKOUT";
        if (path.endsWith("/cancel")) return "CANCEL";
        return "AUDIT_READ";
    }

    private static void reject(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":{}}");
    }
}