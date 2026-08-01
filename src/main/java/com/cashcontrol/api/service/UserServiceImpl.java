package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.EmailVerificationToken;
import com.cashcontrol.api.domain.entity.Role;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.entity.UserRole;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.UserAdminResponse;
import com.cashcontrol.api.dto.response.UserConsentResponse;
import com.cashcontrol.api.dto.response.UserProfileResponse;
import com.cashcontrol.api.dto.response.UserSummaryResponse;
import com.cashcontrol.api.repository.EmailVerificationTokenRepository;
import com.cashcontrol.api.repository.UserConsentRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.RoleRepository;
import com.cashcontrol.api.repository.UserPermissionRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.repository.UserRoleRepository;
import com.cashcontrol.api.security.PermissionResolver;
import com.cashcontrol.api.util.DataMasker;
import com.cashcontrol.api.util.TokenHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.toList;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final RoleRepository roleRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final UserConsentRepository userConsentRepository;
    private final LookupCache lookupCache;
    private final AuditService auditService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;
    private final DataMasker dataMasker;
    private final PermissionResolver permissionResolver;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getOwnProfile(UUID userId) {
        User user = requireUser(userId);
        return toProfileResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserAdminResponse getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
        return toAdminResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserSummaryResponse> listUsers(UUID accountStatusId, Pageable pageable) {
        Page<User> page = accountStatusId != null
                ? userRepository.findAllByDeletedAtIsNullAndAccountStatusId(accountStatusId, pageable)
                : userRepository.findAllByDeletedAtIsNull(pageable);
        return page.map(this::toSummaryResponse);
    }

    @Override
    @Transactional
    public UserProfileResponse updateOwnProfile(UUID userId, String displayName) {
        User user = requireUser(userId);
        user.setDisplayName(displayName);
        userRepository.save(user);
        return toProfileResponse(user);
    }

    @Override
    @Transactional
    public void disableUser(UUID actorId, UUID targetUserId, String reason) {
        User user = requireUser(targetUserId);
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_INACTIVE));
        user.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(user);

        auditService.record(AuditEventSlug.USER_DISABLED, AuditOutcomeSlug.SUCCESS, actorId, targetUserId,
                Map.of("reason", reason != null ? reason : ""));
        auditService.record(AuditEventSlug.CREDENTIALS_INVALIDATED, AuditOutcomeSlug.SUCCESS, actorId, targetUserId,
                Map.of("reason", "ACCOUNT_DISABLED"));
    }

    @Override
    @Transactional
    public void activateUser(UUID actorId, UUID targetUserId) {
        User user = requireUser(targetUserId);
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        userRepository.save(user);

        auditService.record(AuditEventSlug.USER_ACTIVATED, AuditOutcomeSlug.SUCCESS, actorId, targetUserId);
    }

    @Override
    @Transactional
    public void softDeleteUser(UUID actorId, UUID targetUserId) {
        User user = requireUser(targetUserId);
        user.setDeletedAt(Instant.now());
        user.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(user);

        auditService.record(AuditEventSlug.USER_DELETED, AuditOutcomeSlug.SUCCESS, actorId, targetUserId);
        auditService.record(AuditEventSlug.CREDENTIALS_INVALIDATED, AuditOutcomeSlug.SUCCESS, actorId, targetUserId,
                Map.of("reason", "ACCOUNT_DELETED"));
    }

    @Override
    @Transactional
    public void adminCreateUser(UUID actorId, String email, List<UUID> roleIds) {
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new ConflictException("E-mail já cadastrado.");
        }

        User user = new User();
        user.setEmail(email);
        user.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_PENDING_VERIFICATION));
        user.setAuthOrigin(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_LOCAL));
        user.setCredentialsUpdatedAt(Instant.now());
        User saved = userRepository.save(user);

        if (roleIds != null) {
            for (UUID roleId : roleIds) {
                roleRepository.findById(roleId).ifPresent(role -> {
                    UserRole ur = new UserRole();
                    ur.setUser(saved);
                    ur.setRole(role);
                    ur.setGrantedBy(userRepository.getReferenceById(actorId));
                    userRoleRepository.save(ur);
                });
            }
        }

        String rawToken = UUID.randomUUID().toString();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUser(saved);
        token.setTokenHash(TokenHasher.sha256(rawToken));
        token.setExpiresAt(Instant.now().plus(
                appProperties.getSecurity().getEmailVerificationExpiryHours(), ChronoUnit.HOURS));
        emailVerificationTokenRepository.save(token);

        emailService.sendEmailVerification(saved.getEmail(), rawToken, null);

        auditService.record(AuditEventSlug.USER_CREATED, AuditOutcomeSlug.SUCCESS, actorId, saved.getId(),
                Map.of("email", dataMasker.maskEmail(saved.getEmail())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserConsentResponse> getConsentHistory(UUID userId) {
        requireUser(userId);
        return userConsentRepository.findByUserIdOrderByAcceptedAtDesc(userId).stream()
                .map(c -> new UserConsentResponse(
                        c.getId(),
                        c.getConsentVersion(),
                        c.getAcceptedAt(),
                        c.getRevokedAt(),
                        c.getRevokedAt() == null))
                .collect(toList());
    }

    // ── mapping helpers ──────────────────────────────────────────────────────

    private User requireUser(UUID userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    private List<String> roleNames(UUID userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(ur -> ur.getRole().getName())
                .toList();
    }

    private List<String> directPermissionNames(UUID userId) {
        return userPermissionRepository.findByUserId(userId).stream()
                .map(up -> up.getPermission().getName())
                .toList();
    }

    private UserProfileResponse toProfileResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                dataMasker.maskEmail(user.getEmail()),
                user.getDisplayName(),
                user.getAccountStatus().getSlug(),
                user.getAuthOrigin().getSlug(),
                user.getLastLoginAt(),
                roleNames(user.getId()),
                permissionResolver.resolveEffectivePermissions(user.getId()),
                user.getCreatedAt()
        );
    }

    private UserAdminResponse toAdminResponse(User user) {
        String lockoutStatus = user.getLockoutType() != null ? user.getLockoutType().getSlug() : null;
        return new UserAdminResponse(
                user.getId(),
                dataMasker.maskEmail(user.getEmail()),
                user.getDisplayName(),
                user.getAccountStatus().getSlug(),
                user.getAuthOrigin().getSlug(),
                user.getLastLoginAt(),
                roleNames(user.getId()),
                directPermissionNames(user.getId()),
                user.getCreatedAt(),
                user.getFailedLoginAttempts(),
                lockoutStatus,
                user.getLockoutExpiresAt(),
                user.getDeletedAt() != null
        );
    }

    private UserSummaryResponse toSummaryResponse(User user) {
        return new UserSummaryResponse(
                user.getId(),
                dataMasker.maskEmail(user.getEmail()),
                user.getAccountStatus().getSlug(),
                user.getAuthOrigin().getSlug(),
                user.getLastLoginAt()
        );
    }
}