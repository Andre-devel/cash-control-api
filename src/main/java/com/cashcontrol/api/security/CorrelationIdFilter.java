package com.cashcontrol.api.security;

import com.cashcontrol.api.audit.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String MDC_CORRELATION_ID = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        UUID correlationId = UUID.randomUUID();
        response.setHeader(CORRELATION_ID_HEADER, correlationId.toString());
        MDC.put(MDC_CORRELATION_ID, correlationId.toString());

        log.debug("Incoming request [{} {}]", request.getMethod(), request.getRequestURI());

        String ip = extractIp(request);
        String userAgent = request.getHeader("User-Agent");

        try {
            ScopedValue.where(CorrelationIdHolder.CORRELATION_ID, correlationId)
                    .where(RequestContext.IP, ip != null ? ip : "")
                    .where(RequestContext.USER_AGENT, userAgent != null ? userAgent : "")
                    .run(() -> invokeChain(request, response, chain));
        } catch (FilterExecutionException e) {
            e.rethrow();
        } finally {
            MDC.clear();
        }
    }

    private static String extractIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static void invokeChain(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        try {
            chain.doFilter(req, res);
        } catch (IOException | ServletException e) {
            throw new FilterExecutionException(e);
        }
    }

    private static final class FilterExecutionException extends RuntimeException {
        FilterExecutionException(Throwable cause) {
            super(cause);
        }

        void rethrow() throws ServletException, IOException {
            Throwable cause = getCause();
            if (cause instanceof IOException e) throw e;
            if (cause instanceof ServletException e) throw e;
            throw new RuntimeException(cause);
        }
    }
}