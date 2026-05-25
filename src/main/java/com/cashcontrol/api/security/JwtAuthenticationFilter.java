package com.cashcontrol.api.security;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MDC_USER_ID = "userId";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final AuthorityMapper authorityMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        extractBearerToken(request).ifPresent(token -> authenticate(token, request));
        chain.doFilter(request, response);
    }

    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return Optional.of(header.substring(BEARER_PREFIX.length()));
        }
        return Optional.empty();
    }

    private void authenticate(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.validateAndParseClaims(token);
            UUID userId = jwtService.extractUserId(claims);
            Instant issuedAt = jwtService.extractIssuedAt(claims);

            User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
            if (user == null) {
                return;
            }

            if (issuedAt.isBefore(user.getCredentialsUpdatedAt())) {
                log.debug("JWT rejected: issued before credentials_updated_at for user {}", userId);
                return;
            }

            if (!UserSlugConstants.STATUS_ACTIVE.equals(user.getAccountStatus().getSlug())) {
                log.debug("JWT rejected: user {} is not ACTIVE", userId);
                return;
            }

            List<String> permissionNames = jwtService.extractAuthorities(claims);
            List<GrantedAuthority> authorities = authorityMapper.fromPermissionList(permissionNames);
            AuthenticatedUser principal = new AuthenticatedUser(user, authorities);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            MDC.put(MDC_USER_ID, userId.toString());

        } catch (TokenExpiredException e) {
            log.trace("JWT token expired — not recording AUTH_FAILURE (normal stateless expiry)");
        } catch (Exception e) {
            log.debug("JWT authentication failed: {}", e.getMessage());
        }
    }
}