package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.response.SecuritySummaryResponse;

import java.util.UUID;

public interface AdminSecurityService {

    void forceReAuthentication(UUID actorId, UUID targetUserId);

    void manualLockAccount(UUID actorId, UUID targetUserId, String reason);

    void unlockAccount(UUID actorId, UUID targetUserId);

    SecuritySummaryResponse getSecuritySummary();
}