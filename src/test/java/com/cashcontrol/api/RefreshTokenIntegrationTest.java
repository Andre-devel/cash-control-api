package com.cashcontrol.api;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.RefreshToken;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.InvalidCredentialsException;
import com.cashcontrol.api.dto.request.ChangePasswordRequest;
import com.cashcontrol.api.dto.request.LoginRequest;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.RefreshTokenRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.AuthTokens;
import com.cashcontrol.api.service.RefreshTokenService;
import com.cashcontrol.api.util.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenIntegrationTest extends BaseIntegrationTest {

    private static final String PASSWORD = "Str0ng!Pass123";

    @Autowired private AuthService authService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private LookupCache lookupCache;

    @Test
    void refresh_rotatesTokenAndKeepsFamily() {
        AuthTokens login = loginFreshUser();
        String familyId = tokenOf(login.refreshToken()).getFamilyId().toString();

        AuthTokens refreshed = authService.refresh(login.refreshToken(), "127.0.0.1", "TestAgent");

        assertThat(refreshed.response().accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(tokenOf(login.refreshToken()).getRevokedAt()).isNotNull();
        assertThat(tokenOf(refreshed.refreshToken()).getRevokedAt()).isNull();
        assertThat(tokenOf(refreshed.refreshToken()).getFamilyId()).hasToString(familyId);
    }

    @Test
    void refresh_withAlreadyRotatedToken_revokesWholeFamily() {
        AuthTokens login = loginFreshUser();
        AuthTokens refreshed = authService.refresh(login.refreshToken(), "127.0.0.1", "TestAgent");

        assertThatThrownBy(() -> authService.refresh(login.refreshToken(), "127.0.0.1", "TestAgent"))
                .isInstanceOf(InvalidCredentialsException.class);

        // The successor the legitimate client still holds is revoked along with the leaked one
        assertThat(tokenOf(refreshed.refreshToken()).getRevokedAt()).isNotNull();
        assertThatThrownBy(() -> authService.refresh(refreshed.refreshToken(), "127.0.0.1", "TestAgent"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_withExpiredToken_isRejected() {
        AuthTokens login = loginFreshUser();
        RefreshToken stored = tokenOf(login.refreshToken());
        stored.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        refreshTokenRepository.save(stored);

        assertThatThrownBy(() -> authService.refresh(login.refreshToken(), "127.0.0.1", "TestAgent"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refresh_withUnknownToken_isRejected() {
        assertThatThrownBy(() -> authService.refresh("not-a-real-token", "127.0.0.1", "TestAgent"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void revoke_makesTokenUnusable() {
        AuthTokens login = loginFreshUser();

        refreshTokenService.revoke(login.refreshToken());

        assertThatThrownBy(() -> authService.refresh(login.refreshToken(), "127.0.0.1", "TestAgent"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void passwordChange_invalidatesRefreshTokensOfOtherSessions() {
        String email = registerActiveUser();
        AuthTokens otherDevice = authService.login(new LoginRequest(email, PASSWORD), "127.0.0.1", "TestAgent");
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();

        authService.changePassword(user.getId(), new ChangePasswordRequest(PASSWORD, "N3w!Password456"));

        assertThatThrownBy(() -> authService.refresh(otherDevice.refreshToken(), "127.0.0.1", "TestAgent"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private AuthTokens loginFreshUser() {
        return authService.login(new LoginRequest(registerActiveUser(), PASSWORD), "127.0.0.1", "TestAgent");
    }

    private String registerActiveUser() {
        String email = "refresh_" + System.nanoTime() + "@example.com";
        authService.register(new RegisterRequest(email, PASSWORD, true));

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);

        return email;
    }

    private RefreshToken tokenOf(String rawToken) {
        return refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken)).orElseThrow();
    }
}
