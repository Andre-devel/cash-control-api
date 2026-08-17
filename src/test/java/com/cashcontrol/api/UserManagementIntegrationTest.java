package com.cashcontrol.api;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.dto.request.LoginRequest;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AuthService;
import com.cashcontrol.api.service.UserService;
import com.cashcontrol.api.service.NoOpEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private LookupCache lookupCache;
    @Autowired private NoOpEmailService noOpEmailService;

    @BeforeEach
    void clear() {
        noOpEmailService.clearSentEmails();
    }

    private User createActiveUser(String email) {
        authService.register(new RegisterRequest(email, "Str0ng!Pass123", true));
        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.save(user);
    }

    @Test
    void disableUser_preventsLogin() {
        String email = "disable_" + System.nanoTime() + "@example.com";
        User user = createActiveUser(email);

        // Login works before disable
        authService.login(new LoginRequest(email, "Str0ng!Pass123"), null, null);

        userService.disableUser(null, user.getId(), "test");

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "Str0ng!Pass123"), null, null))
                .isInstanceOf(com.cashcontrol.api.domain.exception.InvalidCredentialsException.class);
    }

    @Test
    void softDeleteUser_excludedFromListAndCannotLogin() {
        String email = "softdel_" + System.nanoTime() + "@example.com";
        User user = createActiveUser(email);

        userService.softDeleteUser(null, user.getId());

        assertThat(userRepository.findByEmailAndDeletedAtIsNull(email)).isEmpty();
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "Str0ng!Pass123"), null, null))
                .isInstanceOf(com.cashcontrol.api.domain.exception.InvalidCredentialsException.class);
    }

    @Test
    void activateUser_afterDisable_allowsLogin() {
        String email = "reactivate_" + System.nanoTime() + "@example.com";
        User user = createActiveUser(email);

        userService.disableUser(null, user.getId(), "test disable");
        userService.activateUser(null, user.getId());

        var response = authService.login(new LoginRequest(email, "Str0ng!Pass123"), null, null);
        assertThat(response.response().accessToken()).isNotBlank();
    }
}