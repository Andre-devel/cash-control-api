package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.LockoutType;
import org.springframework.stereotype.Repository;

@Repository
public interface LockoutTypeRepository extends LookupEntityRepository<LockoutType> {
}