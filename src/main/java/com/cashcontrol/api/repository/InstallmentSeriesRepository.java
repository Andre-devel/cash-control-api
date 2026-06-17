package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.InstallmentSeries;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InstallmentSeriesRepository extends JpaRepository<InstallmentSeries, UUID> {

    Optional<InstallmentSeries> findByIdAndUserId(UUID id, UUID userId);

    List<InstallmentSeries> findAllByUserIdAndIsSettledFalse(UUID userId);

    List<InstallmentSeries> findAllByUserId(UUID userId);
}
