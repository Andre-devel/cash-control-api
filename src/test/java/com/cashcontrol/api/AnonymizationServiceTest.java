package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.AnonymizationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnonymizationServiceTest {

    @InjectMocks
    private AnonymizationServiceImpl anonymizationService;

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;

    @Test
    void anonymizeUser_softDeletedUser_zerosPiiFields() {
        UUID userId = UUID.randomUUID();
        User user = buildSoftDeletedUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        anonymizationService.anonymizeUser(userId);

        assertThat(user.getEmail()).startsWith("anonymized-").endsWith("@deleted.invalid");
        assertThat(user.getDisplayName()).isNull();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getAnonymizedAt()).isNotNull();
        assertThat(user.getId()).isEqualTo(userId);
    }

    @Test
    void anonymizeUser_softDeletedUser_recordsAuditEvent() {
        UUID userId = UUID.randomUUID();
        User user = buildSoftDeletedUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        anonymizationService.anonymizeUser(userId);

        verify(auditService).record(
                eq(AuditEventSlug.USER_ANONYMIZED),
                eq(AuditOutcomeSlug.SUCCESS),
                isNull(),
                eq(userId),
                anyMap());
    }

    @Test
    void anonymizeUser_notSoftDeleted_throwsConflictException() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setEmail("user@example.com");
        user.setPasswordHash("hash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> anonymizationService.anonymizeUser(userId))
                .isInstanceOf(ConflictException.class);

        verify(userRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), anyMap());
    }

    @Test
    void anonymizeUser_userNotFound_throwsResourceNotFoundException() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> anonymizationService.anonymizeUser(userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anonymizeUser_alreadyAnonymized_skipsWithoutChanges() {
        UUID userId = UUID.randomUUID();
        User user = buildSoftDeletedUser(userId);
        user.setAnonymizedAt(Instant.now().minusSeconds(3600));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        anonymizationService.anonymizeUser(userId);

        verify(userRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), anyMap());
    }

    @Test
    void anonymizeUser_preservesUuid() {
        UUID userId = UUID.randomUUID();
        User user = buildSoftDeletedUser(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        anonymizationService.anonymizeUser(userId);

        assertThat(user.getId()).isEqualTo(userId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User buildSoftDeletedUser(UUID id) {
        User user = new User();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", id);
        user.setEmail("real@example.com");
        user.setDisplayName("Real Name");
        user.setPasswordHash("$argon2id$hash");
        user.setDeletedAt(Instant.now().minusSeconds(86400));
        return user;
    }
}
