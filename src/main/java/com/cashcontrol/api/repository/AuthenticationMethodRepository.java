package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AuthenticationMethod;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthenticationMethodRepository extends LookupEntityRepository<AuthenticationMethod> {
}