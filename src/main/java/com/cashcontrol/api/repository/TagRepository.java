package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findAllByUserId(UUID userId);

    List<Tag> findAllByUserIdAndIdIn(UUID userId, List<UUID> ids);

    Optional<Tag> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndName(UUID userId, String name);
}
