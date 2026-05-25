package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.OauthProvider;
import org.springframework.stereotype.Repository;

@Repository
public interface OauthProviderRepository extends LookupEntityRepository<OauthProvider> {
}