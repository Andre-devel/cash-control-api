package com.cashcontrol.api.service.openfinance;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.BankAccountKind;
import com.cashcontrol.api.domain.entity.BankConnection;
import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import com.cashcontrol.api.domain.entity.BankProvider;
import com.cashcontrol.api.domain.exception.BankProviderException;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.LinkBankAccountRequest;
import com.cashcontrol.api.dto.response.BankConnectionAccountResponse;
import com.cashcontrol.api.dto.response.BankConnectionResponse;
import com.cashcontrol.api.dto.response.BankConnectionsResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.BankConnectionAccountRepository;
import com.cashcontrol.api.repository.BankConnectionRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyAccount;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyItem;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Conexões com bancos pela Pluggy. Ver o cabeçalho da V38.
 *
 * <p>O widget roda no navegador e devolve só o id do item; quem diz de quem é o item é a própria
 * Pluggy ({@code clientUserId}, gravado no connect token). Por isso o cadastro confere o dono na
 * Pluggy e o webhook, que chega sem assinatura, só serve de aviso para reler o item de lá.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankConnectionService {

    private static final int STATUS_DETAIL_MAX = 255;
    private static final int CONNECTOR_NAME_MAX = 100;

    private final PluggyClient pluggyClient;
    private final AppProperties appProperties;
    private final BankConnectionRepository connectionRepository;
    private final BankConnectionAccountRepository connectionAccountRepository;
    private final AccountRepository accountRepository;
    private final CreditCardRepository creditCardRepository;
    private final BankPendingService bankPendingService;

    @Transactional(readOnly = true)
    public BankConnectionsResponse list(UUID userId) {
        List<BankConnection> connections = connectionRepository.findAllByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, List<BankConnectionAccount>> accounts = connectionAccountRepository
                .findAllByConnectionIdIn(connections.stream().map(BankConnection::getId).toList()).stream()
                .collect(Collectors.groupingBy(BankConnectionAccount::getConnectionId));
        return new BankConnectionsResponse(appProperties.getPluggy().isEnabled(), connections.stream()
                .map(connection -> toResponse(connection, accounts.getOrDefault(connection.getId(), List.of())))
                .toList());
    }

    /** Token para conectar um banco novo ou, com {@code connectionId}, renovar o consentimento dele. */
    @Transactional(readOnly = true)
    public String createConnectToken(UUID userId, UUID connectionId) {
        requireEnabled();
        String itemId = connectionId == null ? null : find(connectionId, userId).getItemId();
        return pluggyClient.createConnectToken(userId.toString(), itemId);
    }

    /** Grava o item que o widget acabou de criar (ou atualizar) e as contas dele. */
    @Transactional
    public BankConnectionResponse register(UUID userId, UUID itemId) {
        requireEnabled();
        String id = itemId.toString();
        PluggyItem item = pluggyClient.getItem(id)
                .filter(found -> userId.toString().equals(found.clientUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Conexão não encontrada."));
        BankConnection connection = connectionRepository.findByProviderAndItemId(BankProvider.PLUGGY, id)
                .orElseGet(() -> {
                    BankConnection created = new BankConnection();
                    created.setUserId(userId);
                    created.setProvider(BankProvider.PLUGGY);
                    created.setItemId(id);
                    return created;
                });
        if (!connection.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Conexão não encontrada.");
        }
        return sync(connection, item);
    }

    /** Relê status e contas na Pluggy — o que o webhook faria, para quando ele não chega (dev). */
    @Transactional
    public BankConnectionResponse refresh(UUID userId, UUID connectionId) {
        requireEnabled();
        BankConnection connection = find(connectionId, userId);
        PluggyItem item = pluggyClient.getItem(connection.getItemId())
                .orElseThrow(() -> new BusinessRuleException(
                        "O banco não reconhece mais esta conexão. Remova e conecte de novo."));
        return sync(connection, item);
    }

    @Transactional
    public BankConnectionResponse link(UUID userId, UUID connectionId, UUID connectionAccountId, LinkBankAccountRequest request) {
        BankConnection connection = find(connectionId, userId);
        BankConnectionAccount bankAccount = connectionAccountRepository.findByIdAndConnectionId(connectionAccountId, connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Conta do banco não encontrada."));
        boolean wrongTarget = bankAccount.getKind() == BankAccountKind.BANK
                ? request.creditCardId() != null
                : request.accountId() != null;
        if (wrongTarget) {
            throw new BusinessRuleException(bankAccount.getKind() == BankAccountKind.BANK
                    ? "Conta do banco só pode ser vinculada a uma conta."
                    : "Cartão do banco só pode ser vinculado a um cartão.");
        }
        if (request.accountId() != null) {
            accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Conta não encontrada."));
            if (connectionAccountRepository.existsByAccountIdAndIdNot(request.accountId(), bankAccount.getId())) {
                throw new ConflictException("Esta conta já está vinculada a outra conta do banco.");
            }
        }
        if (request.creditCardId() != null) {
            creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(request.creditCardId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Cartão não encontrado."));
            if (connectionAccountRepository.existsByCreditCardIdAndIdNot(request.creditCardId(), bankAccount.getId())) {
                throw new ConflictException("Este cartão já está vinculado a outro cartão do banco.");
            }
        }
        bankAccount.setAccountId(request.accountId());
        bankAccount.setCreditCardId(request.creditCardId());
        connectionAccountRepository.save(bankAccount);
        return toResponse(connection, connectionAccountRepository.findAllByConnectionId(connectionId));
    }

    /** Revoga o consentimento na Pluggy e apaga a conexão. Os lançamentos já importados ficam. */
    public void delete(UUID userId, UUID connectionId) {
        BankConnection connection = find(connectionId, userId);
        if (appProperties.getPluggy().isEnabled()) {
            pluggyClient.deleteItem(connection.getItemId());
        }
        connectionRepository.delete(connection);
    }

    /**
     * Evento {@code item/*} do webhook. O corpo não é confiável: só o id serve, para reler o item
     * na Pluggy. Item que a Pluggy não conhece mais é apagado; item sem conexão aqui é ignorado
     * (quem cria a conexão é o cadastro feito pelo app, que confere o dono).
     */
    @Async
    @Transactional
    public void onItemEvent(UUID itemId) {
        if (!appProperties.getPluggy().isEnabled()) {
            return;
        }
        Optional<BankConnection> connection = connectionRepository.findByProviderAndItemId(BankProvider.PLUGGY, itemId.toString());
        if (connection.isEmpty()) {
            return;
        }
        try {
            pluggyClient.getItem(itemId.toString()).ifPresentOrElse(
                    item -> sync(connection.get(), item),
                    () -> connectionRepository.delete(connection.get()));
        } catch (BankProviderException e) {
            log.warn("Pluggy webhook: could not refresh item {}: {}", itemId, e.getCause() != null ? e.getCause().toString() : e.getMessage());
        }
    }

    /**
     * Evento {@code transactions/created}. Loga o atraso (etapa 6.0): quantos lançamentos a Pluggy
     * recebeu desde {@code createdAtFrom} e quanto tempo passou entre o mais novo deles — pela data
     * do banco e pela chegada na Pluggy — e o aviso. Na conta corrente vinculada, põe os novos na
     * fila de revisão (etapa 6A). Só para conta vinculada de um item cadastrado: o corpo não é
     * confiável e não pode disparar leitura à toa.
     */
    @Async
    public void onTransactionsCreated(UUID itemId, String externalAccountId, Instant createdAtFrom, Instant receivedAt) {
        if (!appProperties.getPluggy().isEnabled()) {
            return;
        }
        Optional<BankConnection> connection = connectionRepository.findByProviderAndItemId(BankProvider.PLUGGY, itemId.toString());
        Optional<BankConnectionAccount> link = connection
                .flatMap(found -> connectionAccountRepository.findAllByConnectionId(found.getId()).stream()
                        .filter(account -> externalAccountId.equals(account.getExternalAccountId()))
                        .findFirst());
        if (link.isEmpty()) {
            return;
        }
        try {
            List<PluggyTransaction> created = pluggyClient.listTransactions(externalAccountId).stream()
                    .filter(tx -> tx.createdAt() != null && (createdAtFrom == null || !tx.createdAt().isBefore(createdAtFrom)))
                    .toList();
            Instant newestDate = created.stream().map(PluggyTransaction::date).filter(Objects::nonNull)
                    .max(Instant::compareTo).orElse(null);
            Instant newestCreatedAt = created.stream().map(PluggyTransaction::createdAt)
                    .max(Instant::compareTo).orElse(null);
            log.info("Pluggy webhook delay: kind={} created={} newestDate={} newestCreatedAt={} "
                            + "minutesSinceBankDate={} minutesSincePluggy={}",
                    link.get().getKind(), created.size(), newestDate, newestCreatedAt,
                    newestDate == null ? null : Duration.between(newestDate, receivedAt).toMinutes(),
                    newestCreatedAt == null ? null : Duration.between(newestCreatedAt, receivedAt).toMinutes());
            if (link.get().getKind() == BankAccountKind.BANK && link.get().getAccountId() != null) {
                bankPendingService.enqueue(connection.get().getUserId(), link.get().getAccountId(), createdAtFrom, receivedAt);
            }
        } catch (BankProviderException | BusinessRuleException e) {
            log.warn("Pluggy webhook: could not read transactions of {}: {}", externalAccountId,
                    e.getCause() != null ? e.getCause().toString() : e.getMessage());
        }
    }

    private BankConnectionResponse sync(BankConnection connection, PluggyItem item) {
        connection.setStatus(item.status() != null ? item.status() : "UNKNOWN");
        connection.setStatusDetail(item.error() != null ? truncate(item.error().message(), STATUS_DETAIL_MAX) : null);
        connection.setLastSyncedAt(item.lastUpdatedAt());
        if (item.connector() != null) {
            connection.setConnectorName(truncate(item.connector().name(), CONNECTOR_NAME_MAX));
        }
        connection = connectionRepository.save(connection);

        UUID connectionId = connection.getId();
        Map<String, BankConnectionAccount> existing = connectionAccountRepository.findAllByConnectionId(connectionId).stream()
                .collect(Collectors.toMap(BankConnectionAccount::getExternalAccountId, Function.identity()));
        List<PluggyAccount> remote = pluggyClient.listAccounts(connection.getItemId());
        for (PluggyAccount account : remote) {
            BankConnectionAccount local = existing.remove(account.id());
            if (local == null) {
                local = new BankConnectionAccount();
                local.setConnectionId(connectionId);
                local.setExternalAccountId(account.id());
                local.setKind("CREDIT".equals(account.type()) ? BankAccountKind.CREDIT : BankAccountKind.BANK);
            }
            String name = account.marketingName() != null ? account.marketingName() : account.name();
            local.setName(truncate(name != null ? name : account.id(), 255));
            local.setNumber(truncate(account.number(), 64));
            connectionAccountRepository.save(local);
        }
        // O banco deixou de compartilhar a conta: o vínculo some com ela.
        connectionAccountRepository.deleteAll(existing.values());
        return toResponse(connection, connectionAccountRepository.findAllByConnectionId(connectionId));
    }

    private BankConnection find(UUID connectionId, UUID userId) {
        return connectionRepository.findByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conexão não encontrada."));
    }

    private void requireEnabled() {
        if (!appProperties.getPluggy().isEnabled()) {
            throw new BusinessRuleException("A conexão com bancos não está configurada neste servidor.");
        }
    }

    private static BankConnectionResponse toResponse(BankConnection connection, List<BankConnectionAccount> accounts) {
        return new BankConnectionResponse(connection.getId(), connection.getItemId(), connection.getConnectorName(), connection.getStatus(),
                connection.getStatusDetail(), connection.getLastSyncedAt(), accounts.stream()
                .sorted(Comparator.comparing(BankConnectionAccount::getKind).thenComparing(BankConnectionAccount::getName))
                .map(account -> new BankConnectionAccountResponse(account.getId(), account.getKind(), account.getName(),
                        account.getNumber(), account.getAccountId(), account.getCreditCardId()))
                .toList());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
