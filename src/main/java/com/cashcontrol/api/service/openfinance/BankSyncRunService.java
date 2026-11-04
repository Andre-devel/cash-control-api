package com.cashcontrol.api.service.openfinance;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.BankConnection;
import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import com.cashcontrol.api.domain.entity.BankSyncOutcome;
import com.cashcontrol.api.domain.entity.BankSyncRun;
import com.cashcontrol.api.dto.response.BankConnectionAccountResponse;
import com.cashcontrol.api.dto.response.BankSyncOverviewResponse;
import com.cashcontrol.api.dto.response.BankSyncOverviewResponse.ConnectionSummary;
import com.cashcontrol.api.dto.response.BankSyncRunResponse;
import com.cashcontrol.api.repository.BankConnectionAccountRepository;
import com.cashcontrol.api.repository.BankConnectionRepository;
import com.cashcontrol.api.repository.BankSyncRunRepository;
import com.cashcontrol.api.scheduler.BankSyncScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Histórico das leituras de itens da Pluggy (V44): grava cada uma, lista para a tela do ADMIN e
 * apaga o que passou da retenção. Cada usuário vê só as próprias conexões.
 */
@Service
@Slf4j
public class BankSyncRunService {

    static final Set<BankSyncOutcome> OK = Set.of(BankSyncOutcome.SUCCESS, BankSyncOutcome.NO_CHANGE);
    static final Set<BankSyncOutcome> PROBLEMS = Set.of(BankSyncOutcome.ERROR, BankSyncOutcome.PARTIAL);
    private static final CronExpression POLL = CronExpression.parse(BankSyncScheduler.POLL_CRON);

    private final BankSyncRunRepository runRepository;
    private final BankConnectionRepository connectionRepository;
    private final BankConnectionAccountRepository connectionAccountRepository;
    private final AppProperties appProperties;
    private final ObjectProvider<BankSyncScheduler> scheduler;
    private final TransactionTemplate newTransaction;

    public BankSyncRunService(BankSyncRunRepository runRepository, BankConnectionRepository connectionRepository,
                              BankConnectionAccountRepository connectionAccountRepository, AppProperties appProperties, ObjectProvider<BankSyncScheduler> scheduler,
                              PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.connectionRepository = connectionRepository;
        this.connectionAccountRepository = connectionAccountRepository;
        this.appProperties = appProperties;
        this.scheduler = scheduler;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Grava em transação própria, para a linha de erro ficar mesmo quando a leitura desfaz a dela.
     * Falhar aqui não pode derrubar a sincronização: só loga.
     */
    public void record(BankSyncRun run) {
        if (run.getErrorMessage() != null && run.getErrorMessage().length() > BankSyncRun.ERROR_MESSAGE_MAX) {
            run.setErrorMessage(run.getErrorMessage().substring(0, BankSyncRun.ERROR_MESSAGE_MAX));
        }
        try {
            newTransaction.executeWithoutResult(status -> runRepository.save(run));
        } catch (RuntimeException e) {
            log.warn("Bank sync log: could not record run of item {}: {}", run.getItemId(), e.toString());
        }
    }

    @Transactional(readOnly = true)
    public Page<BankSyncRunResponse> list(UUID userId, UUID connectionId, Collection<BankSyncOutcome> outcomes,
                                          Pageable pageable) {
        Specification<BankSyncRun> spec = (root, query, cb) -> cb.equal(root.get("userId"), userId);
        if (connectionId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("connectionId"), connectionId));
        }
        if (outcomes != null && !outcomes.isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("outcome").in(outcomes));
        }
        return runRepository.findAll(spec, pageable).map(BankSyncRunResponse::from);
    }

    @Transactional(readOnly = true)
    public BankSyncOverviewResponse overview(UUID userId, Instant now) {
        Instant dayAgo = now.minus(Duration.ofHours(24));
        Map<UUID, List<BankSyncRun>> lastDay = runRepository.findAllByUserIdAndStartedAtGreaterThanEqual(userId, dayAgo)
                .stream()
                .filter(run -> run.getConnectionId() != null)
                .collect(Collectors.groupingBy(BankSyncRun::getConnectionId));
        List<BankConnection> owned = connectionRepository.findAllByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, List<BankConnectionAccount>> accounts = connectionAccountRepository
                .findAllByConnectionIdIn(owned.stream().map(BankConnection::getId).toList()).stream()
                .collect(Collectors.groupingBy(BankConnectionAccount::getConnectionId));
        List<ConnectionSummary> connections = owned.stream()
                .map(connection -> summary(userId, connection, accounts.getOrDefault(connection.getId(), List.of()),
                        lastDay.getOrDefault(connection.getId(), List.of())))
                .toList();
        return new BankSyncOverviewResponse(nextPoll(now), appProperties.getRetention().getBankSyncRunDays(), connections);
    }

    private ConnectionSummary summary(UUID userId, BankConnection connection, List<BankConnectionAccount> accounts,
                                      List<BankSyncRun> lastDay) {
        BankSyncRun last = runRepository.findFirstByUserIdAndConnectionIdOrderByStartedAtDesc(userId, connection.getId())
                .orElse(null);
        BankSyncRun lastOk = runRepository.findFirstByUserIdAndConnectionIdAndOutcomeInOrderByStartedAtDesc(
                userId, connection.getId(), OK).orElse(null);
        BankSyncRun lastProblem = runRepository.findFirstByUserIdAndConnectionIdAndOutcomeInOrderByStartedAtDesc(
                userId, connection.getId(), PROBLEMS).orElse(null);
        return new ConnectionSummary(connection.getId(), connection.getConnectorName(), connection.getItemId(),
                connection.getStatus(), connection.getStatusDetail(), connection.getLastSyncedAt(),
                accounts.stream()
                        .sorted(Comparator.comparing(BankConnectionAccount::getKind).thenComparing(BankConnectionAccount::getName))
                        .map(account -> new BankConnectionAccountResponse(account.getId(), account.getKind(), account.getName(),
                                account.getNumber(), account.getAccountId(), account.getCreditCardId()))
                        .toList(),
                last == null ? null : last.getStartedAt(),
                last == null ? null : last.getOutcome(),
                lastOk == null ? null : lastOk.getStartedAt(),
                lastProblem == null ? null : lastProblem.getStartedAt(),
                lastProblem == null ? null : lastProblem.getErrorMessage(),
                lastDay.size(),
                lastDay.stream().filter(run -> PROBLEMS.contains(run.getOutcome())).count(),
                lastDay.stream().map(BankSyncRun::getTransactionsQueued).filter(Objects::nonNull)
                        .mapToLong(Integer::longValue).sum());
    }

    private Instant nextPoll(Instant now) {
        if (scheduler.getIfAvailable() == null || !appProperties.getPluggy().isEnabled()) {
            return null;
        }
        ZonedDateTime next = POLL.next(now.atZone(ZoneId.systemDefault()));
        return next == null ? null : next.toInstant();
    }

    /** Apaga o histórico mais velho que {@code app.retention.bank-sync-run-days}. */
    @Transactional
    public int purge(Instant now) {
        int days = appProperties.getRetention().getBankSyncRunDays();
        int deleted = runRepository.deleteStartedBefore(now.minus(days, ChronoUnit.DAYS));
        log.info("Bank sync log: deleted {} run(s) older than {} days", deleted, days);
        return deleted;
    }
}
