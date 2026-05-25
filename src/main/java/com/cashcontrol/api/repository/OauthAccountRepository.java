package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.OauthAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OauthAccountRepository extends JpaRepository<OauthAccount, UUID> {

    Optional<OauthAccount> findByProviderIdAndProviderUserIdAndUnlinkedAtIsNull(UUID providerId, String providerUserId);

    Optional<OauthAccount> findByUserIdAndProviderIdAndUnlinkedAtIsNull(UUID userId, UUID providerId);
}