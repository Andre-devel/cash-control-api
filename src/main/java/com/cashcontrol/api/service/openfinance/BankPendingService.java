package com.cashcontrol.api.service.openfinance;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.BankPendingTransaction;
import com.cashcontrol.api.domain.entity.PushSubscription;
import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.ImportCommitRequest;
import com.cashcontrol.api.dto.request.ImportCommitRow;
import com.cashcontrol.api.dto.response.BankPendingConfirmAllResponse;
import com.cashcontrol.api.dto.response.BankPendingConfirmResponse;
import com.cashcontrol.api.dto.response.BankPendingItem;
import com.cashcontrol.api.dto.response.ImportPreviewRow;
import com.cashcontrol.api.dto.response.ImportResultResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.BankPendingTransactionRepository;
import com.cashcontrol.api.service.StatementImportService;
import com.cashcontrol.api.service.push.PushService;
import com.cashcontrol.api.service.statement.ParsedStatement;
import com.cashcontrol.api.service.statement.ParsedStatementRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final StatementImportService statementImportService;
    private final PushService pushService;
    private final SecureRandom random = new SecureRandom();

    // ── Entrada pelo webhook ─────────────────────────────────────────────────

    /**
     * Põe na fila o que chegou na Pluggy desde {@code createdAtFrom} na conta corrente vinculada
     * e avisa os aparelhos do usuário. O que já está na fila (ou foi descartado) ou já virou
     * lançamento não entra de novo, então reenvio do mesmo evento não duplica.
     */
    @Transactional
    public void enqueue(UUID userId, UUID accountId, Instant createdAtFrom, Instant receivedAt) {
        Instant since = createdAtFrom != null ? createdAtFrom : receivedAt.minus(DEFAULT_LOOKBACK);
        BankStatement bank = pluggyStatementSource.fetch(accountId, userId, LocalDate.now(BANK_ZONE), since);
        if (bank.externalRefs().isEmpty()) {
            return;
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
            return;
        }
        pendingRepository.saveAll(created);
        log.info("Bank pending: queued {} transaction(s) for account {}", created.size(), accountId);
        notifyQueued(userId, created);
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
            ImportResultResponse result = commit(byId.get(item.id()), suggestedRow(item.row()));
            if (result.errors().isEmpty()) {
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
        ImportPreviewRow preview = classify(pending.getUserId(), List.of(pending)).getFirst().row();
        if (preview.duplicate()) {
            // Já importado pelo diálogo, entre o aviso e o toque: nada a gravar.
            pendingRepository.delete(pending);
            return new BankPendingConfirmResponse(preview.description(), null);
        }
        ImportCommitRow row = edits == null ? suggestedRow(preview) : editedRow(preview, edits);
        ImportResultResponse result = commit(pending, row);
        if (!result.errors().isEmpty()) {
            throw new BusinessRuleException(result.errors().getFirst().message());
        }
        pendingRepository.delete(pending);
        String categoryName = row.categoryId() == null ? null
                : row.categoryId().equals(preview.suggestedCategoryId()) ? preview.suggestedCategoryName() : null;
        return new BankPendingConfirmResponse(row.description(), categoryName);
    }

    private ImportResultResponse commit(BankPendingTransaction pending, ImportCommitRow row) {
        return statementImportService.commit(
                new ImportCommitRequest(pending.getAccountId(), StatementFormat.OPEN_FINANCE, List.of(row), false),
                pending.getUserId());
    }

    /** O que o diálogo de import grava quando o usuário não mexe na linha. */
    static ImportCommitRow suggestedRow(ImportPreviewRow row) {
        boolean movement = MOVEMENT_TYPES.contains(row.type());
        return new ImportCommitRow(row.lineNumber(), row.externalRef(), row.date(),
                row.suggestedDescription() != null ? row.suggestedDescription() : row.description(),
                row.description(), row.amount(), row.type(), row.paymentMethod(),
                movement ? null : row.suggestedCategoryId(), row.outgoing(),
                row.type() == TransactionType.TRANSFER ? row.transferAccountId() : null,
                row.type() == TransactionType.INVOICE_PAYMENT ? row.invoiceId() : null);
    }

    private static ImportCommitRow editedRow(ImportPreviewRow row, ImportCommitRow edits) {
        boolean movement = MOVEMENT_TYPES.contains(edits.type());
        return new ImportCommitRow(row.lineNumber(), row.externalRef(), row.date(), edits.description(),
                row.description(), row.amount(), edits.type(), edits.paymentMethod(),
                movement ? null : edits.categoryId(), row.outgoing(),
                edits.type() == TransactionType.TRANSFER ? edits.transferAccountId() : null,
                edits.type() == TransactionType.INVOICE_PAYMENT ? edits.invoiceId() : null);
    }

    // ── Classificação ────────────────────────────────────────────────────────

    /** Pendências com a classificação da prévia, conta por conta, na ordem recebida. */
    private List<BankPendingItem> classify(UUID userId, List<BankPendingTransaction> pendings) {
        Map<UUID, List<BankPendingTransaction>> byAccount = pendings.stream()
                .collect(Collectors.groupingBy(BankPendingTransaction::getAccountId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, BankPendingItem> classified = new HashMap<>();
        byAccount.forEach((accountId, group) -> {
            Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId).orElse(null);
            if (account == null || account.getArchivedAt() != null) {
                return;
            }
            List<ParsedStatementRow> rows = new ArrayList<>();
            for (BankPendingTransaction pending : group) {
                rows.add(new ParsedStatementRow(rows.size() + 1, pending.getDate(), pending.getHistory(),
                        pending.getDescription(), pending.getAmount()));
            }
            ParsedStatement statement = new ParsedStatement(account.getName(), null, null, rows, List.of());
            List<ImportPreviewRow> preview = statementImportService.previewRows(accountId, statement,
                    group.stream().map(BankPendingTransaction::getExternalRef).toList(), userId).rows();
            for (int i = 0; i < group.size(); i++) {
                classified.put(group.get(i).getId(),
                        new BankPendingItem(group.get(i).getId(), accountId, account.getName(), preview.get(i)));
            }
        });
        return pendings.stream().map(p -> classified.get(p.getId())).filter(item -> item != null).toList();
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
        return pending;
    }

    // ── Notificação ──────────────────────────────────────────────────────────

    /** "Pix enviado · −R$ 615,23" / "Pix Marketplace → Alimentação?" */
    private static String singlePayload(BankPendingItem item, String token, PushSubscription subscription) {
        ImportPreviewRow row = item.row();
        String title = row.rawHistory().isBlank() ? item.accountName() : row.rawHistory();
        if (!subscription.isHideAmounts()) {
            title += " · " + signedMoney(row);
        }
        String body = nameOf(row) + (row.suggestedCategoryName() != null ? " → " + row.suggestedCategoryName() + "?" : "");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "pending");
        payload.put("pendingId", item.id().toString());
        payload.put("token", token);
        payload.put("title", title);
        payload.put("body", body);
        return JSON.writeValueAsString(payload);
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
        String money = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(row.amount())
                .replace(' ', ' ');
        return (row.outgoing() ? "−" : "+") + money;
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
