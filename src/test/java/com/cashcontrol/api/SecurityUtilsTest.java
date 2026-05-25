package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ForbiddenAccessException;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.security.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getCurrentUserId_returnsCorrectUserId_whenAuthenticated() {
        UUID userId = UUID.randomUUID();
        AuthenticatedUser principal = buildAuthenticatedUser(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        assertThat(SecurityUtils.getCurrentUserId()).isEqualTo(userId);
    }

    @Test
    void getCurrentUserId_throwsForbiddenAccessException_whenContextIsEmpty() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(SecurityUtils::getCurrentUserId)
                .isInstanceOf(ForbiddenAccessException.class);
    }

    @Test
    void getCurrentUserId_throwsForbiddenAccessException_whenPrincipalIsNotAuthenticatedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymous", null, List.of()));

        assertThatThrownBy(SecurityUtils::getCurrentUserId)
                .isInstanceOf(ForbiddenAccessException.class);
    }

    private AuthenticatedUser buildAuthenticatedUser(UUID userId) {
        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(userId);
        return new AuthenticatedUser(mockUser, List.of());
    }
}
