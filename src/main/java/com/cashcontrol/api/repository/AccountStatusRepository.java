package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.AccountStatus;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountStatusRepository extends LookupEntityRepository<AccountStatus> {
}