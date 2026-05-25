package com.cashcontrol.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.Optional;
import java.util.UUID;

@NoRepositoryBean
public interface LookupEntityRepository<T> extends JpaRepository<T, UUID> {

    Optional<T> findBySlug(String slug);
}