package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnonymizationServiceImpl implements AnonymizationService {

    private final UserRepository userRepository;
    private final AuditService auditService;

    @Override
    @Transactional
    public void anonymizeUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        if (user.getDeletedAt() == null) {
            throw new ConflictException(
                    "User must be soft-deleted before anonymization. Call DELETE /users/{id} first.");
        }

        if (user.getAnonymizedAt() != null) {
            log.warn("User {} is already anonymized at {}, skipping", userId, user.getAnonymizedAt());
            return;
        }

        String anonymizedEmail = "anonymized-" + userId + "@deleted.invalid";
        user.setEmail(anonymizedEmail);
        user.setDisplayName(null);
        user.setPasswordHash(null);
        user.setAnonymizedAt(Instant.now());
        userRepository.save(user);

        log.info("User {} anonymized: PII fields zeroed, UUID and audit trail preserved", userId);

        auditService.record(AuditEventSlug.USER_ANONYMIZED, AuditOutcomeSlug.SUCCESS, null, userId,
                Map.of("anonymizedAt", user.getAnonymizedAt().toString()));
    }
}
