package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.response.UserAdminResponse;
import com.cashcontrol.api.dto.response.UserConsentResponse;
import com.cashcontrol.api.dto.response.UserProfileResponse;
import com.cashcontrol.api.dto.response.UserSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface UserService {

    UserProfileResponse getOwnProfile(UUID userId);

    UserAdminResponse getUserById(UUID userId);

    Page<UserSummaryResponse> listUsers(UUID accountStatusId, Pageable pageable);

    UserProfileResponse updateOwnProfile(UUID userId, String displayName);

    void disableUser(UUID actorId, UUID targetUserId, String reason);

    void activateUser(UUID actorId, UUID targetUserId);

    void softDeleteUser(UUID actorId, UUID targetUserId);

    void adminCreateUser(UUID actorId, String email, List<UUID> roleIds);

    List<UserConsentResponse> getConsentHistory(UUID userId);
}