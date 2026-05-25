package com.cashcontrol.api.service;

import com.cashcontrol.api.audit.AuditEventSlug;
import com.cashcontrol.api.audit.AuditOutcomeSlug;
import com.cashcontrol.api.audit.AuditService;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.OauthAccount;
import com.cashcontrol.api.domain.entity.OauthProvider;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.OauthAccountRepository;
import com.cashcontrol.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OAuthProviderServiceImpl implements OAuthProviderService {

    private final UserRepository userRepository;
    private final OauthAccountRepository oauthAccountRepository;
    private final LookupCache lookupCache;
    private final AuditService auditService;

    @Override
    @Transactional
    public void unlinkProvider(UUID userId, String providerSlug) {
        OauthProvider provider = lookupCache.requireOauthProvider(providerSlug);

        OauthAccount oauthAccount = oauthAccountRepository
                .findByUserIdAndProviderIdAndUnlinkedAtIsNull(userId, provider.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active linked account found for provider: " + providerSlug));

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));

        String authOriginSlug = user.getAuthOrigin().getSlug();
        if (UserSlugConstants.ORIGIN_GOOGLE.equals(authOriginSlug) && user.getPasswordHash() == null) {
            throw new ConflictException(
                    "Cannot unlink the only login method. Please set a local password first.");
        }

        oauthAccount.setUnlinkedAt(Instant.now());
        oauthAccountRepository.save(oauthAccount);

        if (UserSlugConstants.ORIGIN_MIXED.equals(authOriginSlug)) {
            user.setAuthOrigin(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_LOCAL));
            user.setCredentialsUpdatedAt(Instant.now());
            userRepository.save(user);
        }

        auditService.record(AuditEventSlug.PROVIDER_UNLINKED, AuditOutcomeSlug.SUCCESS,
                userId, userId,
                Map.of("provider", providerSlug));
    }
}
