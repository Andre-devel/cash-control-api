package com.cashcontrol.api.service.investment;

/**
 * @author Andre
 * 03 de Novembro. de 2026
 */

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.FixedIncomeHolding;
import com.cashcontrol.api.domain.entity.FixedIncomeIndexer;
import com.cashcontrol.api.domain.entity.FixedIncomeKind;
import com.cashcontrol.api.domain.entity.FixedIncomeLiquidity;
import com.cashcontrol.api.domain.entity.FixedIncomeRedemption;
import com.cashcontrol.api.domain.entity.FixedIncomeStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.dto.request.FixedIncomeHoldingRequest;
import com.cashcontrol.api.dto.request.RedeemFixedIncomeRequest;
import com.cashcontrol.api.dto.response.FixedIncomeHoldingResponse;
import com.cashcontrol.api.dto.response.PendingInvestmentTransferResponse;
import com.cashcontrol.api.dto.response.PendingInvestmentTransferResponse.Direction;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.FixedIncomeHoldingRepository;
import com.cashcontrol.api.repository.FixedIncomeRedemptionRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.openfinance.PluggyClient;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyInvestment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Traz da Pluggy a renda fixa de um item para as aplicações da conta de investimento.
 *
 * <ul>
 *   <li>Investimento novo e ativo vira aplicação, ligada à transferência que o pagou quando ela já
 *       foi importada (mesmo valor, até {@value #MATCH_DAYS} dias da data da aplicação);</li>
 *   <li>a aplicação existente recebe o valor bruto do banco como valor informado, e a curva do
 *       indexador continua dele até a próxima leitura;</li>
 *   <li>resgatado no banco ({@code TOTAL_WITHDRAWAL}) é resgatado aqui, ligado à transferência de
 *       volta quando houver uma de valor parecido;</li>
 *   <li>por fim o saldo da conta vira o principal aplicado, com um ajuste só (o banco é a fonte).</li>
 * </ul>
 *
 * <p>Na primeira leitura que traz renda fixa ativa, as aplicações cadastradas à mão na conta são excluídas: o banco passa a
 * ser a fonte, e manter as duas duplicaria o patrimônio. Fundos, ações e afins ficam de fora; o
 * módulo é só de renda fixa.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankInvestmentSync {

    static final int MATCH_DAYS = 5;
    static final String ADJUSTMENT_DESCRIPTION = "Ajuste automático · investimentos do banco";
    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");
    private static final BigDecimal REDEMPTION_TOLERANCE = new BigDecimal("0.02");

    private final PluggyClient pluggyClient;
    private final InvestmentService investmentService;
    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final FixedIncomeHoldingRepository holdingRepository;
    private final FixedIncomeRedemptionRepository redemptionRepository;
    private final TransactionRepository transactionRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * Cada passo em transação própria, fora da de quem chamou: um investimento que o módulo recusa
     * não desfaz os outros, nem a leitura das contas do item. Devolve o que mudou, para o histórico
     * de sincronizações.
     */
    public Result sync(UUID userId, String itemId) {
        List<PluggyInvestment> remote = pluggyClient.listInvestments(itemId).stream()
                .filter(investment -> "FIXED_INCOME".equals(investment.type()) && investment.id() != null)
                .toList();
        if (remote.isEmpty()) {
            return Result.NONE;
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        UUID accountId = tx.execute(status -> investmentAccount(userId).map(Account::getId).orElse(null));
        if (accountId == null) {
            log.info("Bank investments: user {} has no single investment account; {} investments ignored", userId, remote.size());
            return Result.NONE;
        }
        LocalDate today = LocalDate.now(BRAZIL);

        tx.executeWithoutResult(status -> {
            boolean firstSync = holdingRepository.findAllByUserIdAndExternalIdIsNotNullAndDeletedAtIsNull(userId).isEmpty();
            if (firstSync && remote.stream().anyMatch(investment -> creatable(investment, today))) {
                removeManualHoldings(accountId);
            }
        });
        int created = 0;
        int redeemed = 0;
        int failed = 0;
        for (PluggyInvestment investment : remote) {
            try {
                Applied applied = tx.execute(status -> apply(investment, userId, accountId, today));
                if (applied == Applied.CREATED) {
                    created++;
                } else if (applied == Applied.REDEEMED) {
                    redeemed++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Bank investments: investment {} of user {} not applied: {}", investment.id(), userId, e.toString());
            }
        }
        tx.executeWithoutResult(status -> {
            linkLateTransfers(userId, accountId, today);
            Account account = accountRepository.findById(accountId).orElseThrow();
            BigDecimal principal = holdingRepository.findAllByAccountIdAndDeletedAtIsNull(accountId).stream()
                    .filter(holding -> holding.getStatus() == FixedIncomeStatus.ACTIVE)
                    .map(FixedIncomeHolding::getRemainingPrincipal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            accountService.reconcileBalance(account, principal, today, ADJUSTMENT_DESCRIPTION);
        });
        return new Result(created, redeemed, failed);
    }

    /** Aplicações criadas e resgatadas nesta leitura, e as que o módulo recusou. */
    public record Result(int created, int redeemed, int failed) {
        public static final Result NONE = new Result(0, 0, 0);
    }

    private enum Applied { CREATED, REDEEMED, NONE }

    /** A conta de investimento do usuário, se houver uma só: com duas, não há como saber qual. */
    private Optional<Account> investmentAccount(UUID userId) {
        List<Account> accounts = accountRepository.findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNull(userId).stream()
                .filter(account -> account.getType() == AccountType.INVESTMENT)
                .toList();
        return accounts.size() == 1 ? Optional.of(accounts.getFirst()) : Optional.empty();
    }

    /** O que vira aplicação nova: ativo, com valor aplicado e data de compra até hoje. */
    private static boolean creatable(PluggyInvestment investment, LocalDate today) {
        LocalDate appliedAt = localDate(investment.purchaseDate());
        return !"TOTAL_WITHDRAWAL".equals(investment.status()) && positive(investment.amountOriginal())
                && appliedAt != null && !appliedAt.isAfter(today);
    }

    /** Só as ativas e sem resgate: resgate registrado é histórico, com o rendimento já lançado. */
    private void removeManualHoldings(UUID accountId) {
        Instant now = Instant.now();
        for (FixedIncomeHolding holding : holdingRepository.findAllByAccountIdAndDeletedAtIsNull(accountId)) {
            if (holding.getExternalId() == null && holding.getStatus() == FixedIncomeStatus.ACTIVE
                    && !redemptionRepository.existsByHoldingId(holding.getId())) {
                holding.setDeletedAt(now);
                log.info("Bank investments: manual holding {} replaced by the bank's", holding.getId());
            }
        }
    }

    /**
     * O id do banco vale para o usuário todo (índice único da V43): a aplicação pode estar numa conta
     * que não é mais a de investimento atual, e ainda assim é atualizada ou resgatada.
     */
    private Applied apply(PluggyInvestment investment, UUID userId, UUID accountId, LocalDate today) {
        Optional<FixedIncomeHolding> existing = holdingRepository.findByUserIdAndExternalIdAndDeletedAtIsNull(userId, investment.id());
        boolean withdrawn = "TOTAL_WITHDRAWAL".equals(investment.status());
        if (existing.isEmpty()) {
            if (creatable(investment, today)) {
                create(investment, accountId, userId, today);
                return Applied.CREATED;
            }
            return Applied.NONE;
        }
        FixedIncomeHolding holding = existing.get();
        if (holding.getStatus() != FixedIncomeStatus.ACTIVE) {
            return Applied.NONE;
        }
        if (withdrawn) {
            redeem(holding, investment, userId, today);
            return Applied.REDEEMED;
        }
        refresh(holding, investment, today);
        return Applied.NONE;
    }

    private List<PendingInvestmentTransferResponse> pending(UUID userId, UUID accountId, LocalDate today, Direction direction) {
        return investmentService.listPendingTransfers(userId, today).stream()
                .filter(transfer -> transfer.accountId().equals(accountId) && transfer.direction() == direction)
                .toList();
    }

    /** Transferência de entrada com o mesmo valor, a até {@value #MATCH_DAYS} dias da aplicação. */
    private Optional<PendingInvestmentTransferResponse> fundingFor(BigDecimal principal, LocalDate appliedAt,
                                                                   List<PendingInvestmentTransferResponse> candidates) {
        return candidates.stream()
                .filter(transfer -> transfer.amount().compareTo(principal) == 0)
                .filter(transfer -> daysApart(transfer.date(), appliedAt) <= MATCH_DAYS)
                .min(Comparator.comparingLong(transfer -> daysApart(transfer.date(), appliedAt)));
    }

    /** Transferência de saída a até 2% do líquido e {@value #MATCH_DAYS} dias da data do resgate. */
    private static Optional<PendingInvestmentTransferResponse> redemptionFor(BigDecimal net, LocalDate date, LocalDate appliedAt,
                                                                             List<PendingInvestmentTransferResponse> candidates) {
        BigDecimal tolerance = net.multiply(REDEMPTION_TOLERANCE).max(BigDecimal.ONE);
        return candidates.stream()
                .filter(candidate -> candidate.amount().subtract(net).abs().compareTo(tolerance) <= 0)
                .filter(candidate -> daysApart(candidate.date(), date) <= MATCH_DAYS)
                .filter(candidate -> !candidate.date().isBefore(appliedAt))
                .min(Comparator.comparing(candidate -> candidate.amount().subtract(net).abs()));
    }

    private void create(PluggyInvestment investment, UUID accountId, UUID userId, LocalDate today) {
        LocalDate appliedAt = localDate(investment.purchaseDate());
        BigDecimal principal = money(investment.amountOriginal());
        UUID funding = fundingFor(principal, appliedAt, pending(userId, accountId, today, Direction.APPLICATION))
                .map(PendingInvestmentTransferResponse::transferGroupId).orElse(null);

        Rate rate = rateOf(investment);
        FixedIncomeKind kind = kindOf(investment.subtype());
        if (kind != FixedIncomeKind.OTHER && rate.indexer() == null) {
            kind = FixedIncomeKind.OTHER;
        }
        LocalDate maturity = localDate(investment.dueDate());
        if (maturity != null && !maturity.isAfter(appliedAt)) {
            maturity = null;
        }
        LocalDate grace = localDate(investment.gracePeriodDate());
        FixedIncomeLiquidity liquidity = maturity == null || grace == null || !grace.isAfter(today)
                ? FixedIncomeLiquidity.DAILY : FixedIncomeLiquidity.AT_MATURITY;

        FixedIncomeHoldingRequest request = new FixedIncomeHoldingRequest(accountId, kind, rate.indexer(), rate.value(),
                truncate(investment.name() != null ? investment.name() : "Aplicação do banco", 100),
                truncate(investment.issuer(), 100), null, liquidity, appliedAt, maturity, principal,
                null, null, null, funding, null);
        FixedIncomeHoldingResponse created = investmentService.createHolding(request, userId, today);
        FixedIncomeHolding holding = holdingRepository.findById(created.id()).orElseThrow();
        holding.setExternalId(investment.id());
        refresh(holding, investment, today);
    }

    /** O bruto do banco vira o valor informado; sem ele, a curva segue do principal. */
    private void refresh(FixedIncomeHolding holding, PluggyInvestment investment, LocalDate today) {
        LocalDate valuedAt = localDate(investment.date());
        if (!positive(investment.amount()) || valuedAt == null || valuedAt.isBefore(holding.getAppliedAt())) {
            return;
        }
        holding.setManualValue(money(investment.amount()));
        holding.setManualValueDate(valuedAt.isAfter(today) ? today : valuedAt);
    }

    /** Resgate total na data em que o banco o mostrou; com a transferência de volta, o líquido é o dela. */
    private void redeem(FixedIncomeHolding holding, PluggyInvestment investment, UUID userId, LocalDate today) {
        LocalDate seen = localDate(investment.date());
        LocalDate date = seen == null || seen.isAfter(today) ? today : seen;
        if (date.isBefore(holding.getAppliedAt())) {
            date = holding.getAppliedAt();
        }
        BigDecimal estimated = investmentService.previewRedemption(holding.getId(), date, true, null, userId, today).net();
        Optional<PendingInvestmentTransferResponse> transfer = redemptionFor(estimated, date, holding.getAppliedAt(),
                pending(userId, holding.getAccount().getId(), today, Direction.REDEMPTION));
        investmentService.redeem(holding.getId(), new RedeemFixedIncomeRequest(transfer.map(PendingInvestmentTransferResponse::date)
                .orElse(date), true, null, null, null, transfer.map(PendingInvestmentTransferResponse::transferGroupId)
                .orElse(null)), userId, today);
    }

    /**
     * A transferência pode ser importada depois que o banco mostrou a aplicação ou o resgate (ela
     * espera aprovação no /revisar). A cada varredura, as aplicações do banco ainda sem ela são ligadas
     * à que casar; no resgate, o líquido e o rendimento passam a ser os da transferência.
     */
    private void linkLateTransfers(UUID userId, UUID accountId, LocalDate today) {
        List<FixedIncomeHolding> holdings = holdingRepository.findAllByAccountIdAndDeletedAtIsNull(accountId).stream()
                .filter(holding -> holding.getExternalId() != null)
                .toList();
        List<PendingInvestmentTransferResponse> applications = new ArrayList<>(pending(userId, accountId, today, Direction.APPLICATION));
        for (FixedIncomeHolding holding : holdings) {
            if (holding.getFundingTransferGroupId() == null) {
                fundingFor(holding.getPrincipal(), holding.getAppliedAt(), applications).ifPresent(transfer -> {
                    applications.remove(transfer);
                    holding.setFundingTransferGroupId(transfer.transferGroupId());
                });
            }
        }
        List<PendingInvestmentTransferResponse> redemptions = new ArrayList<>(pending(userId, accountId, today, Direction.REDEMPTION));
        for (FixedIncomeHolding holding : holdings) {
            if (holding.getStatus() != FixedIncomeStatus.REDEEMED) {
                continue;
            }
            for (FixedIncomeRedemption redemption : redemptionRepository.findAllByHoldingIdOrderByRedeemedAtAsc(holding.getId())) {
                if (redemption.getTransferGroupId() != null) {
                    continue;
                }
                redemptionFor(redemption.getNet(), redemption.getRedeemedAt(), holding.getAppliedAt(), redemptions)
                        .ifPresent(transfer -> {
                            redemptions.remove(transfer);
                            link(redemption, transfer);
                        });
            }
        }
    }

    /** O rendimento lançado acompanha o líquido real, quando não muda de sinal; senão o ajuste cobre. */
    private void link(FixedIncomeRedemption redemption, PendingInvestmentTransferResponse transfer) {
        redemption.setTransferGroupId(transfer.transferGroupId());
        BigDecimal yield = transfer.amount().subtract(redemption.getPrincipalPortion());
        if (redemption.getYieldTransactionId() != null) {
            transactionRepository.findById(redemption.getYieldTransactionId())
                    .filter(tx -> (tx.getType() == TransactionType.INCOME) == (yield.signum() > 0) && yield.signum() != 0)
                    .ifPresent(tx -> tx.setAmount(yield.abs()));
        }
        redemption.setNet(transfer.amount());
    }

    record Rate(FixedIncomeIndexer indexer, BigDecimal value) {
    }

    /**
     * {@code rate} com CDI ou Selic é o percentual do índice; {@code fixedAnnualRate} é a parte
     * prefixada — o spread no CDI+ e no IPCA+, a taxa toda no prefixado.
     */
    static Rate rateOf(PluggyInvestment investment) {
        BigDecimal rate = investment.rate();
        BigDecimal fixed = investment.fixedAnnualRate();
        String type = investment.rateType() == null ? "" : investment.rateType().toUpperCase();
        return switch (type) {
            case "CDI" -> positive(fixed) ? new Rate(FixedIncomeIndexer.CDI_SPREAD, fixed)
                    : positive(rate) ? new Rate(FixedIncomeIndexer.CDI_PERCENT, rate) : new Rate(null, null);
            case "SELIC" -> new Rate(FixedIncomeIndexer.SELIC_PERCENT, positive(rate) ? rate : BigDecimal.valueOf(100));
            case "IPCA" -> positive(fixed) ? new Rate(FixedIncomeIndexer.IPCA_SPREAD, fixed) : new Rate(null, null);
            default -> positive(fixed) ? new Rate(FixedIncomeIndexer.PREFIXED, fixed) : new Rate(null, null);
        };
    }

    static FixedIncomeKind kindOf(String subtype) {
        if (subtype == null) {
            return FixedIncomeKind.OTHER;
        }
        return switch (subtype.toUpperCase()) {
            case "CDB" -> FixedIncomeKind.CDB;
            case "LCI" -> FixedIncomeKind.LCI;
            case "LCA" -> FixedIncomeKind.LCA;
            default -> FixedIncomeKind.OTHER;
        };
    }

    private static LocalDate localDate(Instant instant) {
        return instant == null ? null : instant.atZone(BRAZIL).toLocalDate();
    }

    private static long daysApart(LocalDate a, LocalDate b) {
        return Math.abs(ChronoUnit.DAYS.between(a, b));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
