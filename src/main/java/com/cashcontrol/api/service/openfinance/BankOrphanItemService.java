package com.cashcontrol.api.service.openfinance;

/**
 * @author Andre
 * 4 de Novembro. de 2026
 */

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.BankOrphanItem;
import com.cashcontrol.api.domain.entity.BankProvider;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.response.BankOrphanItemResponse;
import com.cashcontrol.api.repository.BankConnectionRepository;
import com.cashcontrol.api.repository.BankOrphanItemRepository;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Itens da Pluggy sem conexão no app (V50). A API da Pluggy não lista os itens de um
 * {@code clientUserId}, então o registro nasce do webhook: item anunciado que não virou conexão.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankOrphanItemService {

    private static final int CONNECTOR_NAME_MAX = 100;

    private final BankOrphanItemRepository orphanRepository;
    private final BankConnectionRepository connectionRepository;
    private final PluggyClient pluggyClient;
    private final AppProperties appProperties;

    /** Registra (ou atualiza) o item de um usuário nosso que não virou conexão. */
    @Transactional
    public void record(PluggyItem item, UUID userId) {
        String connectorName = item.connector() == null ? null : item.connector().name();
        if (connectorName != null && connectorName.length() > CONNECTOR_NAME_MAX) {
            connectorName = connectorName.substring(0, CONNECTOR_NAME_MAX);
        }
        orphanRepository.upsert(item.id(), userId, connectorName);
    }

    /** O item virou conexão: deixa de ser órfão. */
    @Transactional
    public void clear(String itemId) {
        orphanRepository.deleteByItemId(itemId);
    }

    @Transactional(readOnly = true)
    public List<BankOrphanItemResponse> list(UUID userId) {
        return orphanRepository.findAllByUserIdOrderByFirstSeenAtDesc(userId).stream()
                .map(orphan -> new BankOrphanItemResponse(orphan.getItemId(), orphan.getConnectorName(), orphan.getFirstSeenAt()))
                .toList();
    }

    /**
     * Apaga o item na Pluggy e a linha. Relê o item antes: só apaga o que a Pluggy diz ser do
     * usuário. Item que a Pluggy não conhece mais, ou que entretanto virou conexão, só perde a linha
     * — apagar na Pluggy derrubaria a conexão.
     */
    @Transactional
    public void delete(UUID userId, String itemId) {
        BankOrphanItem orphan = orphanRepository.findByItemIdAndUserId(itemId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Item não encontrado."));
        if (connectionRepository.findByProviderAndItemId(BankProvider.PLUGGY, itemId).isEmpty()) {
            if (!appProperties.getPluggy().isEnabled()) {
                throw new BusinessRuleException("A conexão com bancos não está configurada neste servidor.");
            }
            Optional<PluggyItem> item = pluggyClient.getItem(itemId);
            if (item.isPresent()) {
                if (!userId.toString().equals(item.get().clientUserId())) {
                    throw new ResourceNotFoundException("Item não encontrado.");
                }
                pluggyClient.deleteItem(itemId);
                log.info("Pluggy: deleted orphan item {} of user {}", itemId, userId);
            }
        }
        orphanRepository.delete(orphan);
    }
}
