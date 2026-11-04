package com.cashcontrol.api.service.openfinance;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.BankAccountKind;
import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import com.cashcontrol.api.domain.entity.BankPendingTransaction;
import com.cashcontrol.api.domain.entity.CreditCard;
import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.PushSubscription;
import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRow;
import com.cashcontrol.api.dto.request.ImportCommitRequest;
import com.cashcontrol.api.dto.request.ImportCommitRow;
import com.cashcontrol.api.dto.response.BankPendingConfirmAllResponse;
import com.cashcontrol.api.dto.response.BankPendingConfirmResponse;
import com.cashcontrol.api.dto.response.BankPendingItem;
import com.cashcontrol.api.dto.response.FaturaImportPreviewRow;
import com.cashcontrol.api.dto.response.ImportPreviewRow;
import com.cashcontrol.api.dto.response.ImportRowError;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.BankConnectionAccountRepository;
import com.cashcontrol.api.repository.BankPendingTransactionRepository;
import com.cashcontrol.api.repository.CreditCardRepository;
import com.cashcontrol.api.repository.InvoiceItemRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.FaturaImportService;
import com.cashcontrol.api.service.StatementImportService;
import com.cashcontrol.api.service.fatura.FaturaRowHasher.RowKey;
import com.cashcontrol.api.service.fatura.KeyedFaturaRow;
import com.cashcontrol.api.service.fatura.ParsedFaturaRow;
import com.cashcontrol.api.service.openfinance.PluggyFaturaSource.CardCharge;
import com.cashcontrol.api.service.push.PushService;
import com.cashcontrol.api.service.statement.ParsedStatement;
import com.cashcontrol.api.service.statement.ParsedStatementRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fila de lançamentos que o banco avisou e o usuário ainda não confirmou (etapa 6A).
 *
 * <p>Nada vira transação sem confirmação. A fila guarda só a linha do extrato; tipo, categoria e
 * apelido saem da prévia do import a cada leitura, e confirmar é o commit do import com
 * {@code OPEN_FINANCE} — por isso a fila e o diálogo "Buscar do banco" convivem sem duplicar: a
 * chave {@code plg:} é a mesma, e o que aparece em {@code transactions} sai da fila.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankPendingService {

    /** Até esse tanto de pendentes, uma notificação por lançamento; acima, uma só, de resumo. */
    static final int INDIVIDUAL_NOTIFICATION_LIMIT = 3;
    static final Duration ACTION_TOKEN_LIFETIME = Duration.ofDays(7);
    /** Sem o corte no evento, só o que chegou na Pluggy no último dia — nunca o histórico todo. */
    private static final Duration DEFAULT_LOOKBACK = Duration.ofDays(1);
    private static final ZoneId BANK_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<TransactionType> MOVEMENT_TYPES = Set.of(TransactionType.TRANSFER, TransactionType.INVOICE_PAYMENT);

    private final BankPendingTransactionRepository pendingRepository;
    private final AccountRepository accountRepository;
    private final PluggyStatementSource pluggyStatementSource;
    private final PluggyFaturaSource pluggyFaturaSource;
    private final InvoiceItemRepository invoiceItemRepository;
    private final StatementImportService statementImportService;
    private final PushService pushService;
    private final FaturaImportService faturaImportService;
    private final CreditCardRepository creditCardRepository;
    private final TransactionRepository transactionRepository;
    private final BankConnectionAccountRepository connectionAccountRepository;
    private final SecureRandom random = new SecureRandom();

    // ── Entrada pelo webhook ─────────────────────────────────────────────────

    /**
     * Põe na fila o que chegou na Pluggy desde {@code createdAtFrom} na conta corrente vinculada
     * e avisa os aparelhos do usuário. O que já está na fila (ou foi descartado) ou já virou
     * lançamento não entra de novo, então reenvio do mesmo evento não duplica.
     *
     * <p>Transação própria: quem chama durante a releitura do item (varredura, evento {@code item/*})
     * segue com o sincronismo gravado mesmo que a leitura dos lançamentos falhe.
     *
     * @return quantos lançamentos entraram na fila agora
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int enqueue(UUID userId, UUID accountId, Instant createdAtFrom, Instant receivedAt) {
        Instant since = createdAtFrom != null ? createdAtFrom : receivedAt.minus(DEFAULT_LOOKBACK);
        BankStatement bank = pluggyStatementSource.fetch(accountId, userId, LocalDate.now(BANK_ZONE), since);
        if (bank.externalRefs().isEmpty()) {
            return 0;
        }
        Set<String> queued = new HashSet<>(pendingRepository.findExistingExternalRefs(accountId, bank.externalRefs()));
        List<BankPendingTransaction> created = new ArrayList<>();
        for (int i = 0; i < bank.externalRefs().size(); i++) {
            String ref = bank.externalRefs().get(i);
            if (queued.add(ref)) {
                created.add(toPending(userId, accountId, ref, bank.statement().rows().get(i)));
            }
        }
        if (created.isEmpty()) {
            return 0;
        }
        pendingRepository.saveAll(created);
        log.info("Bank pending: queued {} transaction(s) for account {}", created.size(), accountId);
        notifyQueued(userId, created);
        return created.size();
    }

    /**
     * O mesmo para a fatura atual do cartão vinculado. A chave é a do {@code FaturaRowHasher}, a
     * mesma da prévia da fatura, então o que o diálogo "Buscar do banco" já importou (item de
     * fatura com a chave) não entra, e o que entrar aqui e for importado por lá sai da fila.
     *
     * @return quantas compras entraram na fila agora
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int enqueueCard(UUID userId, UUID creditCardId, Instant createdAtFrom, Instant receivedAt) {
        Instant since = createdAtFrom != null ? createdAtFrom : receivedAt.minus(DEFAULT_LOOKBACK);
        List<CardCharge> charges = pluggyFaturaSource.fetchCurrentSince(creditCardId, userId, since);
        if (charges.isEmpty()) {
            return 0;
        }
        List<String> refs = charges.stream().map(charge -> charge.key().externalRef()).toList();
        Set<String> skipped = new HashSet<>(pendingRepository.findExistingCardExternalRefs(creditCardId, refs));
        invoiceItemRepository.findAllByExternalRefIn(userId, refs).forEach(item -> skipped.add(item.getExternalRef()));
        List<BankPendingTransaction> created = new ArrayList<>();
        for (CardCharge charge : charges) {
            if (skipped.add(charge.key().externalRef())) {
                created.add(toCardPending(userId, creditCardId, charge));
            }
        }
        if (created.isEmpty()) {
            return 0;
        }
        pendingRepository.saveAll(created);
        log.info("Bank pending: queued {} card charge(s) for credit card {}", created.size(), creditCardId);
        notifyQueued(userId, created);
        return created.size();
    }

    private void notifyQueued(UUID userId, List<BankPendingTransaction> created) {
        long open = pendingRepository.countByUserIdAndDiscardedAtIsNull(userId);
        List<BankPendingItem> items = classify(userId, created);
        Instant now = Instant.now();
        created.forEach(pending -> pending.setNotifiedAt(now));
        if (open <= INDIVIDUAL_NOTIFICATION_LIMIT) {
            for (BankPendingItem item : items) {
                BankPendingTransaction pending = created.stream().filter(p -> p.getId().equals(item.id())).findFirst().orElseThrow();
                String token = issueActionToken(pending, now);
                pushService.send(userId, subscription -> singlePayload(item, token, subscription));
            }
        } else if (!items.isEmpty()) {
            pushService.send(userId, subscription -> summaryPayload(open, items.getLast(), subscription));
        }
    }

    private String issueActionToken(BankPendingTransaction pending, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pending.setActionTokenHash(sha256(token));
        pending.setActionTokenExpiresAt(now.plus(ACTION_TOKEN_LIFETIME));
        return token;
    }

    // ── Fila ─────────────────────────────────────────────────────────────────

    @Transactional
    public List<BankPendingItem> list(UUID userId) {
        pendingRepository.deleteImported(userId);
        return classify(userId, pendingRepository.findAllByUserIdAndDiscardedAtIsNullOrderByDateAscCreatedAtAsc(userId));
    }

    @Transactional
    public long count(UUID userId) {
        pendingRepository.deleteImported(userId);
        return pendingRepository.countByUserIdAndDiscardedAtIsNull(userId);
    }

    /**
     * Grava o lançamento. Sem {@code edits}, como a prévia sugere; com elas, tipo, descrição,
     * categoria e o outro lado da movimentação vêm do usuário — data, valor e chave nunca, são
     * do banco.
     */
    @Transactional
    public BankPendingConfirmResponse confirm(UUID userId, UUID pendingId, ImportCommitRow edits) {
        BankPendingTransaction pending = pendingRepository.findByIdAndUserIdAndDiscardedAtIsNull(pendingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Lançamento pendente não encontrado."));
        return confirm(pending, edits);
    }

    /** O botão Confirmar da notificação: sem sessão, autorizado só pelo token daquela pendência. */
    @Transactional
    public BankPendingConfirmResponse confirmByToken(UUID pendingId, String token) {
        BankPendingTransaction pending = pendingRepository.findById(pendingId)
                .filter(found -> found.getDiscardedAt() == null && found.getActionTokenHash() != null)
                .filter(found -> found.getActionTokenExpiresAt() != null && found.getActionTokenExpiresAt().isAfter(Instant.now()))
                .filter(found -> MessageDigest.isEqual(found.getActionTokenHash().getBytes(StandardCharsets.US_ASCII),
                        sha256(token).getBytes(StandardCharsets.US_ASCII)))
                .orElseThrow(() -> new ResourceNotFoundException("Confirmação inválida ou expirada. Abra o app para revisar."));
        return confirm(pending, null);
    }

    /** Grava como sugerido tudo o que não pode ser lançamento repetido; o resto fica na fila. */
    @Transactional
    public BankPendingConfirmAllResponse confirmAll(UUID userId) {
        List<BankPendingItem> items = list(userId);
        Map<UUID, BankPendingTransaction> byId = pendingRepository.findAllById(items.stream().map(BankPendingItem::id).toList())
                .stream().collect(Collectors.toMap(BankPendingTransaction::getId, p -> p));
        int confirmed = 0;
        for (BankPendingItem item : items) {
            if (item.row().possibleDuplicate()) {
                continue;
            }
            BankPendingTransaction pending = byId.get(item.id());
            ImportCommitRow row = pending.getCreditCardId() != null ? cardRow(item.row(), null) : suggestedRow(item.row());
            if (commit(pending, row).isEmpty()) {
                pendingRepository.delete(byId.get(item.id()));
                confirmed++;
            }
        }
        return new BankPendingConfirmAllResponse(confirmed, items.size() - confirmed);
    }

    /** Some da fila e não volta: a linha fica marcada para o webhook não a trazer de novo. */
    @Transactional
    public void discard(UUID userId, UUID pendingId) {
        BankPendingTransaction pending = pendingRepository.findByIdAndUserIdAndDiscardedAtIsNull(pendingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Lançamento pendente não encontrado."));
        pending.setDiscardedAt(Instant.now());
        pending.setActionTokenHash(null);
        pending.setActionTokenExpiresAt(null);
    }

    private BankPendingConfirmResponse confirm(BankPendingTransaction pending, ImportCommitRow edits) {
        List<BankPendingItem> items = classify(pending.getUserId(), List.of(pending));
        if (items.isEmpty()) {
            throw new BusinessRuleException("A conta ou o cartão deste lançamento não aceita mais lançamentos.");
        }
        ImportPreviewRow preview = items.getFirst().row();
        if (preview.duplicate()) {
            // Já importado pelo diálogo, entre o aviso e o toque: nada a gravar.
            pendingRepository.delete(pending);
            return new BankPendingConfirmResponse(preview.description(), null);
        }
        ImportCommitRow row = pending.getCreditCardId() != null
                ? cardRow(preview, edits)
                : edits == null ? suggestedRow(preview) : editedRow(preview, edits);
        List<ImportRowError> errors = commit(pending, row);
        if (!errors.isEmpty()) {
            throw new BusinessRuleException(errors.getFirst().message());
        }
        pendingRepository.delete(pending);
        String categoryName = row.categoryId() == null ? null
                : row.categoryId().equals(preview.suggestedCategoryId()) ? preview.suggestedCategoryName() : null;
        return new BankPendingConfirmResponse(row.description(), categoryName);
    }

    /**
     * Na conta corrente, o commit do import do extrato. No cartão, o da fatura, com esta linha só na
     * fatura em que ela cai: é ele que agrupa por compra e cria (ou reaproveita) a série da parcelada.
     * Do {@code row} o cartão usa só descrição e categoria; o resto é do banco.
     */
    private List<ImportRowError> commit(BankPendingTransaction pending, ImportCommitRow row) {
        if (pending.getCreditCardId() == null) {
            return statementImportService.commit(
                    new ImportCommitRequest(pending.getAccountId(), StatementFormat.OPEN_FINANCE, List.of(row), false),
                    pending.getUserId()).errors();
        }
        UUID userId = pending.getUserId();
        FaturaImportCommitRow cardRow = new FaturaImportCommitRow(1, pending.getCreditCardId(), pending.getCardLast4(),
                pending.getExternalRef(), pending.getOrdinal(), pending.getPurchaseDate(), row.description(),
                pending.getDescription(), pending.getAmount().abs(), pending.getInstallmentNumber(),
                pending.getTotalInstallments(), row.categoryId(), pending.getAmount().signum() > 0);
        return faturaImportService.commit(new FaturaImportCommitRequest(InvoiceImportFormat.OPEN_FINANCE,
                faturaImportService.referenceMonthForCharge(pending.getCreditCardId(), pending.getDate(), userId),
                accountForCard(userId, pending.getCreditCardId()), List.of(cardRow)), userId).errors();
    }

    /**
     * A conta em que entram as compras do cartão (o import da fatura pede uma; ela só importa quando
     * a fatura for paga): a da última compra do cartão, para a parcela cair na conta da série que
     * ela continua; sem compra ainda, a conta corrente vinculada da mesma conexão; sem ela, a
     * primeira conta ativa.
     */
    private UUID accountForCard(UUID userId, UUID creditCardId) {
        Optional<UUID> lastUsed = transactionRepository.findAccountIdsByCreditCard(userId, creditCardId, PageRequest.of(0, 1))
                .stream().findFirst();
        Optional<UUID> linked = connectionAccountRepository.findByCreditCardId(creditCardId).stream()
                .flatMap(card -> connectionAccountRepository.findAllByConnectionId(card.getConnectionId()).stream())
                .filter(account -> account.getKind() == BankAccountKind.BANK && account.getAccountId() != null)
                .map(BankConnectionAccount::getAccountId)
                .findFirst();
        return Stream.concat(lastUsed.stream(), linked.stream())
                .map(id -> accountRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId).orElse(null))
                .filter(account -> account != null && account.getArchivedAt() == null)
                .map(Account::getId)
                .findFirst()
                .or(() -> accountRepository.findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId)
                        .stream().map(Account::getId).findFirst())
                .orElseThrow(() -> new BusinessRuleException("Cadastre uma conta para lançar as compras do cartão."));
    }

    /**
     * O cartão grava como a prévia da fatura sugere; a edição na fila mexe só em descrição e
     * categoria.
     */
    private static ImportCommitRow cardRow(ImportPreviewRow row, ImportCommitRow edits) {
        String description = edits != null ? edits.description()
                : row.suggestedDescription() != null ? row.suggestedDescription() : row.description();
        UUID categoryId = edits != null ? edits.categoryId() : row.suggestedCategoryId();
        return new ImportCommitRow(row.lineNumber(), row.externalRef(), row.date(), description, row.description(),
                row.amount(), row.type(), row.paymentMethod(), categoryId, row.outgoing(), null, null, null, null);
    }

    /** O que o diálogo de import grava quando o usuário não mexe na linha. */
    static ImportCommitRow suggestedRow(ImportPreviewRow row) {
        boolean movement = MOVEMENT_TYPES.contains(row.type());
        return new ImportCommitRow(row.lineNumber(), row.externalRef(), row.date(),
                row.suggestedDescription() != null ? row.suggestedDescription() : row.description(),
                row.description(), row.amount(), row.type(), row.paymentMethod(),
                movement ? null : row.suggestedCategoryId(), row.outgoing(),
                row.type() == TransactionType.TRANSFER ? row.transferAccountId() : null,
                row.type() == TransactionType.INVOICE_PAYMENT ? row.invoiceId() : null,
                row.counterpartyName(), row.counterpartyDocument());
    }

    private static ImportCommitRow editedRow(ImportPreviewRow row, ImportCommitRow edits) {
        boolean movement = MOVEMENT_TYPES.contains(edits.type());
        return new ImportCommitRow(row.lineNumber(), row.externalRef(), row.date(), edits.description(),
                row.description(), row.amount(), edits.type(), edits.paymentMethod(),
                movement ? null : edits.categoryId(), row.outgoing(),
                edits.type() == TransactionType.TRANSFER ? edits.transferAccountId() : null,
                edits.type() == TransactionType.INVOICE_PAYMENT ? edits.invoiceId() : null,
                row.counterpartyName(), row.counterpartyDocument());
    }

    // ── Classificação ────────────────────────────────────────────────────────

    /**
     * Pendências com a classificação da prévia, na ordem recebida: as da conta corrente pela prévia
     * do extrato, conta por conta; as do cartão pela da fatura, cartão e fatura por vez. A de conta ou
     * cartão excluído ou arquivado fica de fora.
     */
    private List<BankPendingItem> classify(UUID userId, List<BankPendingTransaction> pendings) {
        Map<UUID, BankPendingItem> classified = new HashMap<>();
        pendings.stream()
                .filter(pending -> pending.getAccountId() != null)
                .collect(Collectors.groupingBy(BankPendingTransaction::getAccountId, LinkedHashMap::new, Collectors.toList()))
                .forEach((accountId, group) -> classifyAccount(userId, accountId, group, classified));
        pendings.stream()
                .filter(pending -> pending.getCreditCardId() != null)
                .collect(Collectors.groupingBy(BankPendingTransaction::getCreditCardId, LinkedHashMap::new, Collectors.toList()))
                .forEach((creditCardId, group) -> classifyCard(userId, creditCardId, group, classified));
        return pendings.stream().map(p -> classified.get(p.getId())).filter(item -> item != null).toList();
    }

    private void classifyAccount(UUID userId, UUID accountId, List<BankPendingTransaction> group,
                                 Map<UUID, BankPendingItem> classified) {
        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId).orElse(null);
        if (account == null || account.getArchivedAt() != null) {
            return;
        }
        List<ParsedStatementRow> rows = new ArrayList<>();
        for (BankPendingTransaction pending : group) {
            rows.add(new ParsedStatementRow(rows.size() + 1, pending.getDate(), pending.getHistory(),
                    pending.getDescription(), pending.getAmount(), pending.getCounterpartyName(),
                    pending.getCounterpartyDocument()));
        }
        ParsedStatement statement = new ParsedStatement(account.getName(), null, null, rows, List.of());
        List<ImportPreviewRow> preview = statementImportService.previewRows(accountId, statement,
                group.stream().map(BankPendingTransaction::getExternalRef).toList(), userId).rows();
        for (int i = 0; i < group.size(); i++) {
            classified.put(group.get(i).getId(),
                    new BankPendingItem(group.get(i).getId(), accountId, account.getName(), preview.get(i)));
        }
    }

    /**
     * A prévia da fatura com a chave guardada na fila, não recalculada: o ordinal foi contado
     * sobre a fatura inteira. Cada compra é classificada contra a fatura em que ela cai.
     */
    private void classifyCard(UUID userId, UUID creditCardId, List<BankPendingTransaction> group,
                              Map<UUID, BankPendingItem> classified) {
        CreditCard card = creditCardRepository.findByIdAndUserIdAndDeletedAtIsNull(creditCardId, userId).orElse(null);
        if (card == null || card.getArchivedAt() != null) {
            return;
        }
        Map<String, List<BankPendingTransaction>> byInvoice = group.stream().collect(Collectors.groupingBy(
                pending -> faturaImportService.referenceMonthForCharge(creditCardId, pending.getDate(), userId),
                LinkedHashMap::new, Collectors.toList()));
        byInvoice.forEach((referenceMonth, pendings) -> {
            List<KeyedFaturaRow> rows = new ArrayList<>();
            for (BankPendingTransaction pending : pendings) {
                rows.add(new KeyedFaturaRow(pending.getCardLast4(), new RowKey(pending.getExternalRef(), pending.getOrdinal()),
                        new ParsedFaturaRow(rows.size() + 1, pending.getPurchaseDate(), pending.getDescription(),
                                pending.getAmount(), pending.getInstallmentNumber(), pending.getTotalInstallments())));
            }
            List<FaturaImportPreviewRow> preview = faturaImportService.previewKeyedRows(creditCardId, referenceMonth, rows, userId);
            for (int i = 0; i < pendings.size(); i++) {
                BankPendingTransaction pending = pendings.get(i);
                classified.put(pending.getId(), new BankPendingItem(pending.getId(), null, null, creditCardId,
                        card.getName(), pending.getInstallmentNumber(), pending.getTotalInstallments(),
                        toStatementRow(pending, preview.get(i))));
            }
        });
    }

    /** A linha da prévia da fatura no formato da fila, que é o da prévia do extrato. */
    private static ImportPreviewRow toStatementRow(BankPendingTransaction pending, FaturaImportPreviewRow row) {
        return new ImportPreviewRow(row.lineNumber(), row.externalRef(), pending.getDate(), row.description(),
                pending.getHistory(), row.amount(), row.refund() ? TransactionType.REFUND : TransactionType.EXPENSE,
                PaymentMethodSlug.CREDIT_CARD, row.merchantKey(), row.suggestedDescription(), row.suggestedCategoryId(),
                row.suggestedCategoryName(), row.suggestedSubcategoryId(), row.suggestedSubcategoryName(),
                row.suggestionSource(), row.duplicate(), false, !row.refund(), null, null, null, null, false,
                List.of(), false, false, row.possibleDuplicate(), null, null);
    }

    private static BankPendingTransaction toPending(UUID userId, UUID accountId, String ref, ParsedStatementRow row) {
        BankPendingTransaction pending = new BankPendingTransaction();
        pending.setUserId(userId);
        pending.setAccountId(accountId);
        pending.setExternalRef(ref);
        pending.setDate(row.date());
        pending.setHistory(truncate(row.rawHistory()));
        pending.setDescription(truncate(row.description()));
        pending.setAmount(row.signedAmount());
        pending.setCounterpartyName(row.counterpartyName());
        pending.setCounterpartyDocument(row.counterpartyDocument());
        return pending;
    }

    private static BankPendingTransaction toCardPending(UUID userId, UUID creditCardId, CardCharge charge) {
        ParsedFaturaRow row = charge.row();
        BankPendingTransaction pending = new BankPendingTransaction();
        pending.setUserId(userId);
        pending.setCreditCardId(creditCardId);
        pending.setExternalRef(charge.key().externalRef());
        pending.setCardLast4(charge.cardLast4());
        pending.setOrdinal(charge.key().ordinal());
        pending.setDate(charge.postedOn());
        pending.setPurchaseDate(row.date());
        pending.setHistory(truncate(row.description()));
        pending.setDescription(truncate(row.description()));
        pending.setAmount(row.signedAmount());
        pending.setInstallmentNumber(row.installmentNumber());
        pending.setTotalInstallments(row.totalInstallments());
        return pending;
    }

    // ── Notificação ──────────────────────────────────────────────────────────

    /**
     * "Pix enviado · −R$ 615,23" / "Para Fulano → Alimentação?" (ou "De Fulano" na entrada). Sem
     * contraparte, o nome do lançamento: "Pix Marketplace → Alimentação?". No cartão, ver {@link #cardTitle};
     * o corpo é "{descrição} → {categoria}?".
     */
    private static String singlePayload(BankPendingItem item, String token, PushSubscription subscription) {
        ImportPreviewRow row = item.row();
        String title;
        String who;
        if (item.creditCardId() != null) {
            title = cardTitle(item, subscription.isHideAmounts());
            who = nameOf(row);
        } else {
            title = row.rawHistory().isBlank() ? item.accountName() : row.rawHistory();
            if (!subscription.isHideAmounts()) {
                title += " · " + signedMoney(row);
            }
            who = row.counterpartyName() != null
                    ? (row.outgoing() ? "Para " : "De ") + row.counterpartyName()
                    : nameOf(row);
        }
        String body = who + (row.suggestedCategoryName() != null ? " → " + row.suggestedCategoryName() + "?" : "");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "pending");
        payload.put("pendingId", item.id().toString());
        payload.put("token", token);
        payload.put("title", title);
        payload.put("body", body);
        return JSON.writeValueAsString(payload);
    }

    /**
     * "Compra no cartão · R$ 300,00"; na parcelada, "Compra 10x no cartão · R$ 30,00/mês" (o valor
     * é o da parcela); no estorno, "Estorno no cartão · R$ 40,00". Sem valores, só o começo.
     */
    private static String cardTitle(BankPendingItem item, boolean hideAmounts) {
        ImportPreviewRow row = item.row();
        boolean refund = row.type() == TransactionType.REFUND;
        boolean parceled = !refund && item.totalInstallments() != null && item.totalInstallments() > 1;
        String title = refund ? "Estorno no cartão"
                : parceled ? "Compra " + item.totalInstallments() + "x no cartão"
                : "Compra no cartão";
        return hideAmounts ? title : title + " · " + money(row) + (parceled ? "/mês" : "");
    }

    /** "5 lançamentos para revisar" / "Pix Marketplace −R$ 615,23 e mais 4" */
    private static String summaryPayload(long open, BankPendingItem latest, PushSubscription subscription) {
        String body = nameOf(latest.row()) + (subscription.isHideAmounts() ? "" : " " + signedMoney(latest.row()))
                      + " e mais " + (open - 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "summary");
        payload.put("count", open);
        payload.put("title", open + " lançamentos para revisar");
        payload.put("body", body);
        return JSON.writeValueAsString(payload);
    }

    private static String nameOf(ImportPreviewRow row) {
        return row.suggestedDescription() != null ? row.suggestedDescription() : row.description();
    }

    private static String signedMoney(ImportPreviewRow row) {
        return (row.outgoing() ? "−" : "+") + money(row);
    }

    /** "R$ 30,00", com espaço comum: o separador do {@code NumberFormat} é o não separável. */
    private static String money(ImportPreviewRow row) {
        return NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(row.amount()).replace(' ', ' ');
    }

    private static String truncate(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
