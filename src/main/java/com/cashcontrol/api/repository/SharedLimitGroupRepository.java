package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.SharedLimitGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SharedLimitGroupRepository extends JpaRepository<SharedLimitGroup, UUID> {

    List<SharedLimitGroup> findAllByUserId(UUID userId);

    Optional<SharedLimitGroup> findByIdAndUserId(UUID id, UUID userId);
}
