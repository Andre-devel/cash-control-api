package com.cashcontrol.api.repository;

import com.cashcontrol.api.domain.entity.MerchantAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantAliasRepository extends JpaRepository<MerchantAlias, UUID> {

    /**
     * Todos os apelidos do usuário. É uma leitura completa de propósito: a tabela tem uma
     * linha por estabelecimento que o usuário renomeou, o que cabe folgado em memória e
     * dispensa a busca por regex no banco que {@code CategorySuggester} precisa fazer sobre
     * o histórico de transações.
     */
    List<MerchantAlias> findAllByUserId(UUID userId);

    Optional<MerchantAlias> findByUserIdAndMerchantKey(UUID userId, String merchantKey);

    void deleteByUserIdAndMerchantKey(UUID userId, String merchantKey);
}
