package com.cashcontrol.api;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditServiceImpl;
import com.cashcontrol.api.audit.AuditMetadataSanitizer;
import com.cashcontrol.api.domain.entity.AuditEventType;
import com.cashcontrol.api.domain.entity.AuditOutcome;
import com.cashcontrol.api.domain.entity.AuditLog;
import com.cashcontrol.api.repository.AuditLogRepository;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.util.DataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @InjectMocks
    private AuditServiceImpl auditService;

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private LookupCache lookupCache;
    @Mock private AuditMetadataSanitizer sanitizer;
    @Mock private DataMasker dataMasker;
    @Mock private UserRepository userRepository;

    private AuditEventType stubEventType;
    private AuditOutcome stubOutcome;

    @BeforeEach
    void setUp() {
        stubEventType = new AuditEventType();
        stubOutcome = new AuditOutcome();

        when(lookupCache.requireAuditEventType(any())).thenReturn(stubEventType);
        when(lookupCache.requireAuditOutcome(any())).thenReturn(stubOutcome);
        when(sanitizer.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));

        // Inject self-proxy reference (needed for @Async delegation)
        ReflectionTestUtils.setField(auditService, "self", auditService);
    }

    @Test
    void persistAsync_savesAuditLogWithCorrectFields() {
        String maskedIp = "192.168.1.0";
        String ua = "Mozilla/5.0";
        UUID corrId = UUID.randomUUID();

        auditService.persistAsync(
                AuditEventSlug.AUTH_SUCCESS, AuditOutcomeSlug.SUCCESS,
                null, null, null, maskedIp, ua, corrId);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getIpAddressMasked()).isEqualTo(maskedIp);
        assertThat(saved.getUserAgentTruncated()).isEqualTo(ua);
        assertThat(saved.getCorrelationId()).isEqualTo(corrId);
        assertThat(saved.getEventType()).isSameAs(stubEventType);
        assertThat(saved.getOutcome()).isSameAs(stubOutcome);
    }

    @Test
    void persistAsync_sanitizesMetadataBeforeSave() {
        Map<String, Object> rawMeta = Map.of("password", "secret", "userId", "123");
        Map<String, Object> sanitized = Map.of("password", "[REDACTED]", "userId", "123");
        when(sanitizer.sanitize(rawMeta)).thenReturn(sanitized);

        auditService.persistAsync(
                AuditEventSlug.USER_REGISTERED, AuditOutcomeSlug.SUCCESS,
                null, null, rawMeta, null, null, UUID.randomUUID());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getMetadata()).isEqualTo(sanitized);
    }

    @Test
    void persistAsync_swallowsExceptionAndDoesNotPropagate() {
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException("DB failure"));

        // Must not throw
        auditService.persistAsync(
                AuditEventSlug.AUTH_FAILURE, AuditOutcomeSlug.FAILURE,
                null, null, null, null, null, UUID.randomUUID());
    }

    @Test
    void persistAsync_withActorAndTarget_setsUserReferencesByProxy() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(userRepository.getReferenceById(actorId)).thenReturn(new com.cashcontrol.api.domain.entity.User());
        when(userRepository.getReferenceById(targetId)).thenReturn(new com.cashcontrol.api.domain.entity.User());

        auditService.persistAsync(
                AuditEventSlug.ROLE_ASSIGNED, AuditOutcomeSlug.SUCCESS,
                actorId, targetId, null, null, null, UUID.randomUUID());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getActorUser()).isNotNull();
        assertThat(captor.getValue().getTargetUser()).isNotNull();
    }
}