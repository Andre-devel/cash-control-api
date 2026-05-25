package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AuthOrigin;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthOriginRepository extends LookupEntityRepository<AuthOrigin> {
}