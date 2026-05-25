package com.cashcontrol.api.security;

import com.cashcontrol.api.domain.exception.ForbiddenAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new ForbiddenAccessException();
        }
        return principal.getUser().getId();
    }
}
