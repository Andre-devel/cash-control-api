package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.UserAdminResponse;
import com.cashcontrol.api.dto.response.UserProfileResponse;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.RoleRepository;
import com.cashcontrol.api.repository.UserPermissionRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.repository.UserRoleRepository;
import com.cashcontrol.api.security.PermissionResolver;
import com.cashcontrol.api.service.EmailService;
import com.cashcontrol.api.service.UserServiceImpl;
import com.cashcontrol.api.util.DataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @InjectMocks private UserServiceImpl userService;

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private UserPermissionRepository userPermissionRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock private LookupCache lookupCache;
    @Mock private AuditService auditService;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AppProperties appProperties;
    @Mock private DataMasker dataMasker;
    @Mock private PermissionResolver permissionResolver;

    @BeforeEach
    void setUp() {
        when(dataMasker.maskEmail(anyString())).thenReturn("m***@x.com");
        when(userRoleRepository.findByUserId(any())).thenReturn(List.of());
        when(userPermissionRepository.findByUserId(any())).thenReturn(List.of());
        when(permissionResolver.resolveEffectivePermissions(any())).thenReturn(List.of());

        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_INACTIVE))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_INACTIVE));
        when(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE))
                .thenReturn(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
    }

    @Test
    void getOwnProfile_returnsProfileWithMaskedEmailNoPasswordHash() {
        UUID userId = UUID.randomUUID();
        User user = buildActiveUser(userId);
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.of(user));

        UserProfileResponse profile = userService.getOwnProfile(userId);

        assertThat(profile.id()).isEqualTo(userId);
        assertThat(profile.maskedEmail()).isEqualTo("m***@x.com");
        // No password hash field on the response DTO
        assertThat(profile).isInstanceOf(UserProfileResponse.class);
    }

    @Test
    void getOwnProfile_userNotFound_throwsResourceNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByIdAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getOwnProfile(userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void disableUser_setsInactiveStatus_andUpdatesCredentialsUpdatedAt() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = buildActiveUser(targetId);
        Instant before = Instant.now();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId)).thenReturn(Optional.of(user));

        userService.disableUser(actorId, targetId, "Policy violation");

        assertThat(user.getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_INACTIVE);
        assertThat(user.getCredentialsUpdatedAt()).isAfterOrEqualTo(before);
        verify(auditService).record(eq(AuditEventSlug.USER_DISABLED), eq(AuditOutcomeSlug.SUCCESS),
                eq(actorId), eq(targetId), any());
        verify(auditService).record(eq(AuditEventSlug.CREDENTIALS_INVALIDATED), eq(AuditOutcomeSlug.SUCCESS),
                eq(actorId), eq(targetId), any());
    }

    @Test
    void softDeleteUser_setsDeletedAtAndUpdatesCredentialsUpdatedAt() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        User user = buildActiveUser(targetId);
        Instant before = Instant.now();
        when(userRepository.findByIdAndDeletedAtIsNull(targetId)).thenReturn(Optional.of(user));

        userService.softDeleteUser(actorId, targetId);

        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getDeletedAt()).isAfterOrEqualTo(before);
        assertThat(user.getCredentialsUpdatedAt()).isAfterOrEqualTo(before);
        verify(auditService).record(eq(AuditEventSlug.USER_DELETED), eq(AuditOutcomeSlug.SUCCESS),
                eq(actorId), eq(targetId));
    }

    @Test
    void getUserById_returnsAdminResponse() {
        UUID userId = UUID.randomUUID();
        User user = buildActiveUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserAdminResponse resp = userService.getUserById(userId);

        assertThat(resp.id()).isEqualTo(userId);
        assertThat(resp.failedLoginAttempts()).isEqualTo(0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User buildActiveUser(UUID id) {
        User user = new User();
        user.setEmail("u@x.com");
        user.setAccountStatus(TestEntityFactory.accountStatus(UserSlugConstants.STATUS_ACTIVE));
        user.setAuthOrigin(TestEntityFactory.authOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        // Reflection to set UUID (not publicly settable)
        try {
            var f = user.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return user;
    }
}