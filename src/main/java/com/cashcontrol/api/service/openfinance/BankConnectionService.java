package com.cashcontrol.api.service.openfinance;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.BankAccountKind;
import com.cashcontrol.api.domain.entity.BankConnection;
import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import com.cashcontrol.api.domain.entity.BankProvider;
import com.cashcontrol.api.domain.entity.BankSyncOutcome;
import com.cashcontrol.api.domain.entity.BankSyncRun;
import com.cashcontrol.api.domain.entity.BankSyncTrigger;
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
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.service.investment.BankInvestmentSync;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    /** Status do item em que o banco parou de entregar dados até o usuário agir. */
    private static final Set<String> ITEM_ERROR_STATUSES = Set.of("LOGIN_ERROR", "OUTDATED");

    private final PluggyClient pluggyClient;
    private final AppProperties appProperties;
    private final BankConnectionRepository connectionRepository;
    private final BankConnectionAccountRepository connectionAccountRepository;
    private final AccountRepository accountRepository;
    private final CreditCardRepository creditCardRepository;
    private final BankPendingService bankPendingService;
    private final UserRepository userRepository;
    private final BankInvestmentSync bankInvestmentSync;
    private final BankSyncRunService bankSyncRunService;

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
                .orElseGet(() -> newConnection(userId, id));
        if (!connection.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Conexão não encontrada.");
        }
        return tracked(connection, BankSyncTrigger.REGISTER, null, run -> sync(connection, item, run));
    }

    private static BankConnection newConnection(UUID userId, String itemId) {
        BankConnection created = new BankConnection();
        created.setUserId(userId);
        created.setProvider(BankProvider.PLUGGY);
        created.setItemId(itemId);
        return created;
    }

    /** Relê status e contas na Pluggy — o que o webhook faria, para quando ele não chega (dev). */
    @Transactional
    public BankConnectionResponse refresh(UUID userId, UUID connectionId) {
        requireEnabled();
        BankConnection connection = find(connectionId, userId);
        return tracked(connection, BankSyncTrigger.MANUAL, null, run -> {
            PluggyItem item = pluggyClient.getItem(connection.getItemId())
                    .orElseThrow(() -> new BusinessRuleException(
                            "O banco não reconhece mais esta conexão. Remova e conecte de novo."));
            return syncAndQueue(connection, item, run);
        });
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
     * na Pluggy. Item que a Pluggy não conhece mais é apagado.
     *
     * <p>Item sem conexão aqui é cadastrado se a Pluggy disser que é de um usuário nosso: o
     * {@code clientUserId} vem do connect token, que só o servidor emite. Isso cobre o PWA, em que o
     * OAuth do MeuPluggy abre fora do app e o widget nunca chama o {@code onSuccess}.
     */
    @Async
    @Transactional
    public void onItemEvent(UUID itemId, String event) {
        if (!appProperties.getPluggy().isEnabled()) {
            return;
        }
        Optional<BankConnection> connection = connectionRepository.findByProviderAndItemId(BankProvider.PLUGGY, itemId.toString());
        if (connection.isEmpty()) {
            adopt(itemId.toString(), event);
            return;
        }
        try {
            reread(connection.get(), BankSyncTrigger.WEBHOOK, event);
        } catch (BankProviderException e) {
            log.warn("Pluggy webhook: could not refresh item {}: {}", itemId, describe(e));
        }
    }

    private void adopt(String itemId, String event) {
        try {
            pluggyClient.getItem(itemId).ifPresent(item -> ownerOf(item).ifPresent(userId -> {
                BankConnection created = newConnection(userId, itemId);
                tracked(created, BankSyncTrigger.REGISTER, event, run -> sync(created, item, run));
                log.info("Pluggy webhook: registered item {} for user {}", itemId, userId);
            }));
        } catch (BankProviderException e) {
            log.warn("Pluggy webhook: could not read new item {}: {}", itemId, describe(e));
        }
    }

    /** Relê o item na Pluggy; item que ela não conhece mais leva a conexão junto. */
    private void reread(BankConnection connection, BankSyncTrigger trigger, String event) {
        tracked(connection, trigger, event, run -> {
            Optional<PluggyItem> item = pluggyClient.getItem(connection.getItemId());
            if (item.isPresent()) {
                return syncAndQueue(connection, item.get(), run);
            }
            connectionRepository.delete(connection);
            run.setOutcome(BankSyncOutcome.ITEM_GONE);
            run.setErrorMessage("A Pluggy não reconhece mais o item; a conexão foi apagada.");
            return null;
        });
    }

    private Optional<UUID> ownerOf(PluggyItem item) {
        try {
            UUID userId = UUID.fromString(item.clientUserId());
            return userRepository.findByIdAndDeletedAtIsNull(userId).map(user -> userId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** Conexões da Pluggy, para a varredura de hora em hora. */
    @Transactional(readOnly = true)
    public List<UUID> pluggyConnectionIds() {
        if (!appProperties.getPluggy().isEnabled()) {
            return List.of();
        }
        return connectionRepository.findAllByProvider(BankProvider.PLUGGY).stream().map(BankConnection::getId).toList();
    }

    /**
     * Varredura de uma conexão: o mesmo que o evento {@code item/*}. Existe porque a Pluggy não chama
     * o webhook quando o item do MeuPluggy sincroniza (visto em produção em 31/10/2026); sem ela o
     * lançamento novo só aparecia ao abrir "Buscar do banco".
     */
    @Transactional
    public void poll(UUID connectionId) {
        if (!appProperties.getPluggy().isEnabled()) {
            return;
        }
        connectionRepository.findById(connectionId)
                .ifPresent(connection -> reread(connection, BankSyncTrigger.SCHEDULED, null));
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
        BankSyncRun run = newRun(connection.get(), BankSyncTrigger.WEBHOOK, "transactions/created");
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
            run.setWebhookDelayMinutes(newestCreatedAt == null ? null : Duration.between(newestCreatedAt, receivedAt).toMinutes());
            if (link.get().getKind() == BankAccountKind.BANK && link.get().getAccountId() != null) {
                run.setTransactionsQueued(bankPendingService.enqueue(
                        connection.get().getUserId(), link.get().getAccountId(), createdAtFrom, receivedAt));
            }
            run.setOutcome(BankSyncOutcome.SUCCESS);
        } catch (BankProviderException | BusinessRuleException e) {
            log.warn("Pluggy webhook: could not read transactions of {}: {}", externalAccountId, describe(e));
            run.setOutcome(BankSyncOutcome.ERROR);
            run.setErrorMessage(describe(e));
        } finally {
            run.setFinishedAt(Instant.now());
            bankSyncRunService.record(run);
        }
    }

    /**
     * Roda {@code body} e grava a execução no histórico (V44), com o erro quando ele falha — o erro
     * segue para quem chamou. Sem desfecho marcado pelo {@code body}, a execução conta como sucesso.
     */
    private <T> T tracked(BankConnection connection, BankSyncTrigger trigger, String event, Function<BankSyncRun, T> body) {
        BankSyncRun run = newRun(connection, trigger, event);
        try {
            T result = body.apply(run);
            if (run.getOutcome() == null) {
                run.setOutcome(BankSyncOutcome.SUCCESS);
            }
            return result;
        } catch (RuntimeException e) {
            run.setOutcome(BankSyncOutcome.ERROR);
            run.setErrorMessage(describe(e));
            throw e;
        } finally {
            run.setFinishedAt(Instant.now());
            bankSyncRunService.record(run);
        }
    }

    private static BankSyncRun newRun(BankConnection connection, BankSyncTrigger trigger, String event) {
        BankSyncRun run = new BankSyncRun();
        run.setUserId(connection.getUserId());
        run.setConnectionId(connection.getId());
        run.setItemId(connection.getItemId());
        run.setConnectorName(connection.getConnectorName());
        run.setTrigger(trigger);
        run.setWebhookEvent(event);
        run.setStartedAt(Instant.now());
        return run;
    }

    /** Mensagem da exceção e, quando houver, a causa — que é onde a Pluggy diz o que deu errado. */
    private static String describe(RuntimeException e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return e.getCause() != null ? message + " (" + e.getCause() + ")" : message;
    }

    /**
     * {@link #sync} e, se o item sincronizou na Pluggy desde a última leitura, põe na fila de revisão
     * o que chegou nesse meio-tempo nas contas correntes vinculadas. A fila não aceita a mesma linha
     * duas vezes, então coincidir com o {@code transactions/created} não duplica.
     */
    private BankConnectionResponse syncAndQueue(BankConnection connection, PluggyItem item, BankSyncRun run) {
        Instant previous = connection.getLastSyncedAt();
        BankConnectionResponse response = sync(connection, item, run);
        List<String> problems = new ArrayList<>();
        syncInvestments(connection, run, problems);
        boolean bankUpdated = previous == null || (item.lastUpdatedAt() != null && item.lastUpdatedAt().isAfter(previous));
        if (previous != null && bankUpdated) {
            run.setTransactionsQueued(queueSince(connection, previous, problems));
        }
        if (run.getOutcome() == BankSyncOutcome.ERROR) {
            return response;
        }
        if (!problems.isEmpty()) {
            run.setOutcome(BankSyncOutcome.PARTIAL);
            run.setErrorMessage(String.join(" · ", problems));
        } else {
            run.setOutcome(bankUpdated ? BankSyncOutcome.SUCCESS : BankSyncOutcome.NO_CHANGE);
        }
        return response;
    }

    /** Fila de revisão de cada conta corrente vinculada; devolve quantos lançamentos entraram. */
    private int queueSince(BankConnection connection, Instant previous, List<String> problems) {
        int queued = 0;
        Instant now = Instant.now();
        for (BankConnectionAccount account : connectionAccountRepository.findAllByConnectionId(connection.getId())) {
            if (account.getKind() != BankAccountKind.BANK || account.getAccountId() == null) {
                continue;
            }
            try {
                queued += bankPendingService.enqueue(connection.getUserId(), account.getAccountId(), previous, now);
            } catch (BankProviderException | BusinessRuleException e) {
                log.warn("Pluggy sync: could not queue transactions of {}: {}", account.getExternalAccountId(), describe(e));
                problems.add("Fila de revisão de " + account.getName() + ": " + describe(e));
            }
        }
        return queued;
    }

    /** Em transação própria: falha nos investimentos não desfaz a leitura das contas. */
    private void syncInvestments(BankConnection connection, BankSyncRun run, List<String> problems) {
        try {
            BankInvestmentSync.Result result = bankInvestmentSync.sync(connection.getUserId(), connection.getItemId());
            run.setInvestmentsCreated(result.created());
            run.setInvestmentsRedeemed(result.redeemed());
            run.setInvestmentsFailed(result.failed());
            if (result.failed() > 0) {
                problems.add(result.failed() + " investimento(s) não aplicado(s)");
            }
        } catch (RuntimeException e) {
            log.warn("Pluggy sync: could not read investments of item {}: {}", connection.getItemId(), describe(e));
            problems.add("Investimentos: " + describe(e));
        }
    }

    /**
     * Grava status e contas do item. O item com erro do banco (login, consentimento vencido) marca a
     * execução como erro, com a mensagem da Pluggy.
     */
    private BankConnectionResponse sync(BankConnection connection, PluggyItem item, BankSyncRun run) {
        connection.setStatus(item.status() != null ? item.status() : "UNKNOWN");
        connection.setStatusDetail(item.error() != null ? truncate(item.error().message(), STATUS_DETAIL_MAX) : null);
        connection.setLastSyncedAt(item.lastUpdatedAt());
        if (item.connector() != null) {
            connection.setConnectorName(truncate(item.connector().name(), CONNECTOR_NAME_MAX));
        }
        connection = connectionRepository.save(connection);
        run.setConnectionId(connection.getId());
        run.setConnectorName(connection.getConnectorName());
        run.setItemStatus(connection.getStatus());
        run.setItemLastUpdatedAt(item.lastUpdatedAt());
        if (item.error() != null || ITEM_ERROR_STATUSES.contains(connection.getStatus())) {
            run.setOutcome(BankSyncOutcome.ERROR);
            run.setErrorMessage(connection.getStatusDetail() != null ? connection.getStatusDetail() : connection.getStatus());
        }

        UUID connectionId = connection.getId();
        Map<String, BankConnectionAccount> existing = connectionAccountRepository.findAllByConnectionId(connectionId).stream()
                .collect(Collectors.toMap(BankConnectionAccount::getExternalAccountId, Function.identity()));
        List<PluggyAccount> remote = pluggyClient.listAccounts(connection.getItemId());
        run.setAccountsRead(remote.size());
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
