package com.dfygt.dfetl.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class LegacyTaskPathWarningFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LegacyTaskPathWarningFilter.class);
    private static final String LEGACY_PREFIX = "/api/task";
    private static final String NEW_PREFIX = "/api/sync-task";
    private static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<String, Instant> lastWarnByPath = new ConcurrentHashMap<>();

    public LegacyTaskPathWarningFilter() {
        this(Clock.systemUTC());
    }

    LegacyTaskPathWarningFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!isLegacyTaskPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        String rewrittenPath = toNewPath(path);
        if (shouldWarn(path)) {
            log.warn("Legacy sync-task path '{}' is deprecated, migrate to '{}'", path, rewrittenPath);
        }
        filterChain.doFilter(new LegacyTaskPathRequestWrapper(request, rewrittenPath), response);
    }

    boolean shouldWarn(String path) {
        if (!isLegacyTaskPath(path)) {
            return false;
        }
        Instant now = clock.instant();
        AtomicBoolean warn = new AtomicBoolean(false);
        lastWarnByPath.compute(path, (key, lastWarnAt) -> {
            if (lastWarnAt == null || Duration.between(lastWarnAt, now).compareTo(WARN_INTERVAL) >= 0) {
                warn.set(true);
                return now;
            }
            return lastWarnAt;
        });
        return warn.get();
    }

    private boolean isLegacyTaskPath(String path) {
        return LEGACY_PREFIX.equals(path) || path.startsWith(LEGACY_PREFIX + "/");
    }

    String toNewPath(String path) {
        if (!isLegacyTaskPath(path)) {
            return path;
        }
        return NEW_PREFIX + path.substring(LEGACY_PREFIX.length());
    }

    private static final class LegacyTaskPathRequestWrapper extends HttpServletRequestWrapper {
        private final String rewrittenPath;

        private LegacyTaskPathRequestWrapper(HttpServletRequest request, String rewrittenPath) {
            super(request);
            this.rewrittenPath = rewrittenPath;
        }

        @Override
        public String getRequestURI() {
            return rewrittenPath;
        }

        @Override
        public StringBuffer getRequestURL() {
            HttpServletRequest request = (HttpServletRequest) getRequest();
            StringBuffer url = request.getRequestURL();
            int start = url.indexOf(request.getRequestURI());
            if (start < 0) {
                return new StringBuffer(url.toString());
            }
            return new StringBuffer(url.substring(0, start) + rewrittenPath);
        }

        @Override
        public String getServletPath() {
            return rewrittenPath;
        }
    }
}
