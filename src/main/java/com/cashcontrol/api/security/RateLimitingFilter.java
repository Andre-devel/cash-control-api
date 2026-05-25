package com.cashcontrol.api.security;

import com.cashcontrol.api.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Set<String> RATE_LIMITED_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/email/verify/resend"
    );

    private final AppProperties appProperties;
    private final ConcurrentHashMap<String, RateLimitBucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getServletPath();
        if (!RATE_LIMITED_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        int maxRequests = appProperties.getSecurity().getRateLimitRequestsPerMinute();
        long windowMillis = appProperties.getSecurity().getRateLimitWindowSeconds() * 1000L;
        String ip = extractIp(request);

        RateLimitBucket bucket = buckets.computeIfAbsent(ip, k -> new RateLimitBucket());

        if (bucket.tryAcquire(maxRequests, windowMillis)) {
            chain.doFilter(request, response);
        } else {
            long retryAfter = bucket.retryAfterSeconds(windowMillis);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());

            String correlationId = CorrelationIdHolder.get().toString();
            response.getWriter().write(
                    "{\"errorCode\":\"RATE_LIMITED\",\"message\":\"Too many requests.\",\"correlationId\":\"" +
                    correlationId + "\"}");

            log.debug("Rate limit exceeded on path: {}", path);
        }
    }

    private static String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public void clearBucketsForTesting() {
        buckets.clear();
    }

    public void forcePartialFillBucketForTesting(String ip, int count) {
        long now = System.currentTimeMillis();
        RateLimitBucket bucket = buckets.computeIfAbsent(ip, k -> new RateLimitBucket());
        bucket.forceAdd(count, now);
    }

    public void forceExhaustBucketForTesting(String ip) {
        int maxRequests = appProperties.getSecurity().getRateLimitRequestsPerMinute();
        forcePartialFillBucketForTesting(ip, maxRequests);
    }

    public void forceExhaustWithExpiredTimestampsForTesting(String ip) {
        int maxRequests = appProperties.getSecurity().getRateLimitRequestsPerMinute();
        long windowMillis = appProperties.getSecurity().getRateLimitWindowSeconds() * 1000L;
        long expiredTimestamp = System.currentTimeMillis() - windowMillis - 100;
        RateLimitBucket bucket = buckets.computeIfAbsent(ip, k -> new RateLimitBucket());
        bucket.forceAdd(maxRequests, expiredTimestamp);
    }

    static final class RateLimitBucket {

        private final Deque<Long> timestamps = new ArrayDeque<>();

        RateLimitBucket() {}

        synchronized boolean tryAcquire(int maxRequests, long windowMillis) {
            long now = System.currentTimeMillis();
            evictExpired(now, windowMillis);
            if (timestamps.size() < maxRequests) {
                timestamps.add(now);
                return true;
            }
            return false;
        }

        synchronized long retryAfterSeconds(long windowMillis) {
            if (timestamps.isEmpty()) {
                return 1;
            }
            long oldest = timestamps.peek();
            long retryAt = oldest + windowMillis;
            long waitMs = retryAt - System.currentTimeMillis();
            return Math.max(1, (waitMs + 999) / 1000);
        }

        synchronized void forceAdd(int count, long timestamp) {
            for (int i = 0; i < count; i++) {
                timestamps.add(timestamp);
            }
        }

        private void evictExpired(long now, long windowMillis) {
            long cutoff = now - windowMillis;
            while (!timestamps.isEmpty() && timestamps.peek() <= cutoff) {
                timestamps.poll();
            }
        }
    }
}
