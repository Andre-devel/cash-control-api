package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuditEventType;
import com.cashcontrol.api.domain.entity.AuditLog;
import com.cashcontrol.api.domain.entity.AuditOutcome;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.AccountStatusRepository;
import com.cashcontrol.api.repository.AuditEventTypeRepository;
import com.cashcontrol.api.repository.AuditLogRepository;
import com.cashcontrol.api.repository.AuditOutcomeRepository;
import com.cashcontrol.api.repository.AuthOriginRepository;
import com.cashcontrol.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@Transactional
class AuditLogRepositoryTest {

    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditEventTypeRepository auditEventTypeRepository;
    @Autowired private AuditOutcomeRepository auditOutcomeRepository;
    @Autowired private AccountStatusRepository accountStatusRepository;
    @Autowired private AuthOriginRepository authOriginRepository;

    private User targetUser;
    private AuditEventType authSuccessType;
    private AuditOutcome successOutcome;

    @BeforeEach
    void setUp() {
        AccountStatus active = accountStatusRepository.findBySlug("ACTIVE").orElseThrow();
        AuthOrigin local = authOriginRepository.findBySlug("LOCAL").orElseThrow();

        User user = new User();
        user.setEmail("auditlog-test-" + System.nanoTime() + "@example.com");
        user.setAccountStatus(active);
        user.setAuthOrigin(local);
        user.setCredentialsUpdatedAt(Instant.now());
        targetUser = userRepository.save(user);

        authSuccessType = auditEventTypeRepository.findBySlug("AUTH_SUCCESS").orElseThrow();
        successOutcome = auditOutcomeRepository.findBySlug("SUCCESS").orElseThrow();
    }

    private AuditLog buildLog(Instant createdAt) {
        AuditLog log = new AuditLog();
        log.setEventType(authSuccessType);
        log.setOutcome(successOutcome);
        log.setTargetUser(targetUser);
        log.setCorrelationId(UUID.randomUUID());
        log.setCreatedAt(createdAt);
        return log;
    }

    @Test
    void findByTargetUserIdOrderByCreatedAtDescReturnsInReverseChronologicalOrder() {
        Instant now = Instant.now();
        AuditLog older = auditLogRepository.save(buildLog(now.minus(10, ChronoUnit.MINUTES)));
        AuditLog newer = auditLogRepository.save(buildLog(now));

        Page<AuditLog> page = auditLogRepository.findByTargetUserIdOrderByCreatedAtDesc(
                targetUser.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(older.getId());
    }

    @Test
    void findByTargetUserIdReturnsOnlyLogsForThatUser() {
        // Create a second user's log
        User otherUser = new User();
        otherUser.setEmail("other-" + System.nanoTime() + "@example.com");
        otherUser.setAccountStatus(accountStatusRepository.findBySlug("ACTIVE").orElseThrow());
        otherUser.setAuthOrigin(authOriginRepository.findBySlug("LOCAL").orElseThrow());
        otherUser.setCredentialsUpdatedAt(Instant.now());
        otherUser = userRepository.save(otherUser);

        AuditLog targetLog = buildLog(Instant.now());
        auditLogRepository.save(targetLog);

        AuditLog otherLog = buildLog(Instant.now());
        otherLog.setTargetUser(otherUser);
        auditLogRepository.save(otherLog);

        Page<AuditLog> page = auditLogRepository.findByTargetUserIdOrderByCreatedAtDesc(
                targetUser.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void auditLogHasNoUpdatedAtField() throws NoSuchFieldException {
        // Verify the AuditLog entity has no updatedAt field (append-only constraint)
        try {
            AuditLog.class.getDeclaredField("updatedAt");
            assertThat(false).as("AuditLog must not have an updatedAt field").isTrue();
        } catch (NoSuchFieldException e) {
            // expected — AuditLog is append-only and must not have updatedAt
            assertThat(true).isTrue();
        }
    }
}