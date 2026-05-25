package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @EntityGraph(attributePaths = {"accountStatus", "authOrigin"})
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    @EntityGraph(attributePaths = {"accountStatus", "authOrigin"})
    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    Page<User> findAllByDeletedAtIsNull(Pageable pageable);

    Page<User> findAllByDeletedAtIsNullAndAccountStatusId(UUID statusId, Pageable pageable);

    long countByAccountStatusId(UUID statusId);
}