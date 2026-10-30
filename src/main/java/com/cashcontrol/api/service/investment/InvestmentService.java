package com.cashcontrol.api.service.investment;

import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.AccountType;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.FixedIncomeHolding;
import com.cashcontrol.api.domain.entity.FixedIncomeIndexer;
import com.cashcontrol.api.domain.entity.FixedIncomeKind;
import com.cashcontrol.api.domain.entity.FixedIncomeLiquidity;
import com.cashcontrol.api.domain.entity.FixedIncomeRedemption;
import com.cashcontrol.api.domain.entity.FixedIncomeStatus;
import com.cashcontrol.api.domain.entity.MarketIndex;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.FixedIncomeHoldingRequest;
import com.cashcontrol.api.dto.request.ManualValueRequest;
import com.cashcontrol.api.dto.request.RedeemFixedIncomeRequest;
import com.cashcontrol.api.dto.response.FixedIncomeHoldingResponse;
import com.cashcontrol.api.dto.response.FixedIncomeRedemptionResponse;
import com.cashcontrol.api.dto.response.HoldingEvolutionResponse;
import com.cashcontrol.api.dto.response.InvestmentSummaryResponse;
import com.cashcontrol.api.dto.response.PendingInvestmentTransferResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.FixedIncomeHoldingRepository;
import com.cashcontrol.api.repository.FixedIncomeRedemptionRepository;
import com.cashcontrol.api.repository.MarketDataRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.AccountService;
import com.cashcontrol.api.service.TransactionService;
import com.cashcontrol.api.service.marketdata.MarketDataBackfillRequested;
import com.cashcontrol.api.service.marketdata.TreasuryBondPrice;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Aplicações e resgates de renda fixa. Ver o cabeçalho da V37 para o modelo: a conta INVESTMENT
 * é o caixa a custo, a aplicação soma o rendimento por cima, e o resgate grava o rendimento e a
 * transferência que mantêm o saldo da conta coerente.
 *
 * <p>Todas as datas de "hoje" chegam de fora (o controller usa o fuso do mercado), para o
 * cálculo não depender do relógio do servidor.
 */
@Service
@RequiredArgsConstructor
public class InvestmentService {

    static final String YIELD_CATEGORY = "Investimentos";
    static final String YIELD_SUBCATEGORY = "Rendimentos";

    private static final int UPCOMING_MATURITY_DAYS = 90;
    /**
     * Até onde olhar transferências sem aplicação. Saldo antigo entra como "já está na conta";
     * sem o corte, anos de extrato importado ficariam pendentes para sempre.
     */
    static final int PENDING_TRANSFER_DAYS = 90;

    private final FixedIncomeHoldingRepository holdingRepository;
    private final FixedIncomeRedemptionRepository redemptionRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final MarketDataRepository marketDataRepository;
    private final AccountService accountService;
    private final TransactionService transactionService;
    private final FixedIncomeCalculator calculator;
    private final ApplicationEventPublisher eventPublisher;

    // ── Consulta ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<FixedIncomeHoldingResponse> listHoldings(UUID userId, FixedIncomeStatus status, LocalDate today) {
        List<FixedIncomeHolding> holdings = status == null
                ? holdingRepository.findAllByUserIdAndDeletedAtIsNullOrderByAppliedAtAsc(userId)
                : holdingRepository.findAllByUserIdAndStatusAndDeletedAtIsNullOrderByAppliedAtAsc(userId, status);
        MarketRates rates = loadRates(holdings, today);
        return holdings.stream().map(holding -> toResponse(holding, today, rates)).toList();
    }

    @Transactional(readOnly = true)
    public FixedIncomeHoldingResponse getHolding(UUID id, UUID userId, LocalDate today) {
        FixedIncomeHolding holding = findHolding(id, userId);
        return toResponse(holding, today, loadRates(List.of(holding), today));
    }

    @Transactional(readOnly = true)
    public InvestmentSummaryResponse getSummary(UUID userId, LocalDate today) {
        List<FixedIncomeHolding> active = holdingRepository
                .findAllByUserIdAndStatusAndDeletedAtIsNullOrderByAppliedAtAsc(userId, FixedIncomeStatus.ACTIVE);
        MarketRates rates = loadRates(active, today);
        Map<FixedIncomeHolding, Valuation> valuations = new LinkedHashMap<>();
        active.forEach(holding -> valuations.put(holding, calculator.value(holding, today, rates)));

        BigDecimal totalGross = sum(valuations.values(), Valuation::gross);
        List<InvestmentSummaryResponse.UpcomingMaturity> upcoming = valuations.entrySet().stream()
                .filter(entry -> entry.getKey().getMaturityDate() != null)
                .filter(entry -> !entry.getKey().getMaturityDate().isAfter(today.plusDays(UPCOMING_MATURITY_DAYS)))
                .sorted(Comparator.comparing(entry -> entry.getKey().getMaturityDate()))
                .map(entry -> new InvestmentSummaryResponse.UpcomingMaturity(entry.getKey().getId(),
                        entry.getKey().getName(), entry.getKey().getMaturityDate(), entry.getValue().net()))
                .toList();

        return new InvestmentSummaryResponse(
                sum(valuations.values(), Valuation::principal),
                totalGross,
                sum(valuations.values(), Valuation::incomeTax),
                sum(valuations.values(), Valuation::iof),
                sum(valuations.values(), Valuation::net),
                sum(valuations.values(), Valuation::yield),
                valuations.values().stream().anyMatch(Valuation::estimated),
                allocation(valuations, holding -> holding.getKind().name(), totalGross),
                allocation(valuations, InvestmentService::indexerKey, totalGross),
                allocation(valuations, holding -> maturityBucket(holding, today), totalGross),
                upcoming,
                accountAllocations(userId, active));
    }

    @Transactional(readOnly = true)
    public HoldingEvolutionResponse getEvolution(UUID id, UUID userId, LocalDate today) {
        FixedIncomeHolding holding = findHolding(id, userId);
        List<FixedIncomeRedemption> redemptions = redemptionRepository.findAllByHoldingIdOrderByRedeemedAtAsc(holding.getId());
        LocalDate end = holding.getStatus() == FixedIncomeStatus.REDEEMED && !redemptions.isEmpty()
                ? redemptions.getLast().getRedeemedAt()
                : today;
        MarketRates rates = loadRates(List.of(holding), end);

        // TreeSet: a aplicação no último dia do mês coincide com o fechamento do mês.
        NavigableSet<LocalDate> dates = new TreeSet<>();
        dates.add(holding.getAppliedAt());
        for (YearMonth month = YearMonth.from(holding.getAppliedAt()); month.atEndOfMonth().isBefore(end); month = month.plusMonths(1)) {
            dates.add(month.atEndOfMonth());
        }
        if (end.isAfter(holding.getAppliedAt())) {
            dates.add(end);
        }

        List<HoldingEvolutionResponse.Point> points = new ArrayList<>();
        for (LocalDate date : dates) {
            // Base de cada ponto: o que ainda estava aplicado naquela data (resgates posteriores não contam).
            BigDecimal principal = holding.getPrincipal();
            BigDecimal quantity = initialTreasuryQuantity(holding, redemptions);
            for (FixedIncomeRedemption redemption : redemptions) {
                if (redemption.getRedeemedAt().isBefore(date)) {
                    principal = principal.subtract(redemption.getPrincipalPortion());
                    if (quantity != null && redemption.getTreasuryQuantity() != null) {
                        quantity = quantity.subtract(redemption.getTreasuryQuantity());
                    }
                }
            }
            BigDecimal benchmark = principal.multiply(BigDecimal.valueOf(calculator.cdiFactor(holding.getAppliedAt(), date, rates)))
                    .setScale(2, RoundingMode.HALF_EVEN);
            points.add(new HoldingEvolutionResponse.Point(date,
                    calculator.grossAt(holding, principal, quantity, date, rates), benchmark));
        }
        return new HoldingEvolutionResponse(points);
    }

    /**
     * Números do card de investimentos do dashboard. O rendimento do mês é quanto o bruto das
     * aplicações ativas andou desde o fim do mês anterior (ou desde a aplicação, se foi neste
     * mês), sobre a base de hoje — resgates do mês já aparecem como receita.
     */
    @Transactional(readOnly = true)
    public DashboardFigures getDashboardFigures(UUID userId, LocalDate today) {
        List<FixedIncomeHolding> active = holdingRepository
                .findAllByUserIdAndStatusAndDeletedAtIsNullOrderByAppliedAtAsc(userId, FixedIncomeStatus.ACTIVE);
        MarketRates rates = loadRates(active, today);
        LocalDate previousMonthEnd = today.withDayOfMonth(1).minusDays(1);
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal yieldMonth = BigDecimal.ZERO;
        for (FixedIncomeHolding holding : active) {
            Valuation valuation = calculator.value(holding, today, rates);
            gross = gross.add(valuation.gross());
            net = net.add(valuation.net());
            yieldMonth = yieldMonth.add(valuation.gross().subtract(calculator.grossAt(holding,
                    holding.getRemainingPrincipal(), holding.getTreasuryQuantity(), previousMonthEnd, rates)));
        }
        return new DashboardFigures(gross, net, yieldMonth);
    }

    public record DashboardFigures(BigDecimal gross, BigDecimal net, BigDecimal yieldMonth) {
    }

    /**
     * O que o patrimônio a custo ainda não enxerga em cada data: Σ (líquido de IR/IOF − principal)
     * do que estava aplicado naquele dia. Somado ao saldo das contas, dá o patrimônio com os
     * investimentos pelo valor de resgate (decisão I1).
     *
     * <p>Resgate na própria data já conta como feito — o rendimento dele é uma transação daquele
     * dia, dentro do saldo a custo. Aplicação de conta arquivada fica fora, como a conta. Na data
     * de hoje vale o valor da tela de investimentos, com o valor informado pelo banco; nas
     * passadas, a curva sobre o que estava aplicado.
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, BigDecimal> unrealizedNetYield(UUID userId, Collection<LocalDate> dates, LocalDate today) {
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        dates.forEach(date -> result.put(date, BigDecimal.ZERO));
        List<FixedIncomeHolding> holdings = holdingRepository.findAllByUserIdAndDeletedAtIsNullOrderByAppliedAtAsc(userId)
                .stream()
                .filter(holding -> holding.getAccount().getArchivedAt() == null && holding.getAccount().getDeletedAt() == null)
                .filter(holding -> dates.stream().anyMatch(date -> date.isAfter(holding.getAppliedAt())))
                .toList();
        if (holdings.isEmpty()) {
            return result;
        }
        Map<UUID, List<FixedIncomeRedemption>> redemptions = new HashMap<>();
        redemptionRepository.findAllByHoldingIdInOrderByRedeemedAtAsc(holdings.stream().map(FixedIncomeHolding::getId).toList())
                .forEach(r -> redemptions.computeIfAbsent(r.getHolding().getId(), id -> new ArrayList<>()).add(r));
        MarketRates rates = loadRates(holdings, dates.stream().max(Comparator.naturalOrder()).orElseThrow());

        for (FixedIncomeHolding holding : holdings) {
            List<FixedIncomeRedemption> own = redemptions.getOrDefault(holding.getId(), List.of());
            BigDecimal initialQuantity = initialTreasuryQuantity(holding, own);
            for (LocalDate date : dates) {
                if (!date.isAfter(holding.getAppliedAt())) {
                    continue;
                }
                BigDecimal unrealized;
                if (!date.isBefore(today) && holding.getStatus() == FixedIncomeStatus.ACTIVE) {
                    Valuation valuation = calculator.value(holding, today, rates);
                    unrealized = valuation.net().subtract(valuation.principal());
                } else {
                    BigDecimal principal = holding.getPrincipal();
                    BigDecimal quantity = initialQuantity;
                    for (FixedIncomeRedemption redemption : own) {
                        if (!redemption.getRedeemedAt().isAfter(date)) {
                            principal = principal.subtract(redemption.getPrincipalPortion());
                            if (quantity != null && redemption.getTreasuryQuantity() != null) {
                                quantity = quantity.subtract(redemption.getTreasuryQuantity());
                            }
                        }
                    }
                    if (principal.signum() <= 0) {
                        continue;
                    }
                    unrealized = calculator.valueAt(holding, principal, quantity, date, rates).net().subtract(principal);
                }
                result.merge(date, unrealized, BigDecimal::add);
            }
        }
        return result;
    }

    /**
     * Transferências com as contas de investimento dos últimos {@value #PENDING_TRANSFER_DAYS}
     * dias que ainda não viraram aplicação nem resgate. Entre duas contas de investimento não
     * conta: o dinheiro não saiu nem entrou na carteira.
     */
    @Transactional(readOnly = true)
    public List<PendingInvestmentTransferResponse> listPendingTransfers(UUID userId, LocalDate today) {
        List<Transaction> legs = transactionRepository.findUnlinkedInvestmentTransferLegs(
                userId, today.minusDays(PENDING_TRANSFER_DAYS));
        if (legs.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Transaction>> groups = new HashMap<>();
        transactionRepository.findAllByTransferGroupIdIn(legs.stream().map(Transaction::getTransferGroupId).toList())
                .forEach(leg -> groups.computeIfAbsent(leg.getTransferGroupId(), id -> new ArrayList<>()).add(leg));
        List<PendingInvestmentTransferResponse> pending = new ArrayList<>();
        for (Transaction leg : legs) {
            Account counterpart = groups.getOrDefault(leg.getTransferGroupId(), List.of()).stream()
                    .filter(other -> !other.getId().equals(leg.getId()))
                    .map(Transaction::getAccount)
                    .findFirst()
                    .orElse(null);
            if (counterpart != null && counterpart.getType() == AccountType.INVESTMENT) {
                continue;
            }
            pending.add(new PendingInvestmentTransferResponse(leg.getTransferGroupId(),
                    leg.getAccount().getId(), leg.getAccount().getName(),
                    counterpart != null ? counterpart.getName() : null,
                    leg.getPaymentDate(), leg.getAmount().abs(), leg.getDescription(),
                    leg.getAmount().signum() > 0 ? PendingInvestmentTransferResponse.Direction.APPLICATION
                            : PendingInvestmentTransferResponse.Direction.REDEMPTION));
        }
        return pending;
    }

    /** Títulos do Tesouro cotados na data mais recente, para o cadastro escolher tipo e vencimento. */
    @Transactional(readOnly = true)
    public List<TreasuryBondPrice> listTreasuryBonds() {
        return marketDataRepository.findLatestTreasuryBaseDate()
                .map(marketDataRepository::findTreasuryPricesOn)
                .orElse(List.of());
    }

    // ── Cadastro ─────────────────────────────────────────────────────────────

    @Transactional
    public FixedIncomeHoldingResponse createHolding(FixedIncomeHoldingRequest request, UUID userId, LocalDate today) {
        Account account = findInvestmentAccount(request.accountId(), userId);
        if (request.fromAccountId() != null && request.fundingTransferGroupId() != null) {
            throw new BusinessRuleException("Informe a conta de origem ou a transferência existente, não as duas.");
        }

        FixedIncomeHolding holding = new FixedIncomeHolding();
        holding.setUserId(userId);
        holding.setAccount(account);
        holding.setKind(request.kind());
        apply(holding, request, today);

        if (request.fromAccountId() != null) {
            Account source = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.fromAccountId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.fromAccountId()));
            List<Transaction> legs = accountService.transfer(source, account, holding.getPrincipal(),
                    holding.getAppliedAt(), "Aplicação · " + holding.getName());
            holding.setFundingTransferGroupId(legs.getFirst().getTransferGroupId());
        } else if (request.fundingTransferGroupId() != null) {
            holding.setFundingTransferGroupId(validateFundingTransfer(request.fundingTransferGroupId(), holding, userId));
        }

        holdingRepository.save(holding);
        requestBackfillIfNeeded(holding.getAppliedAt());
        return toResponse(holding, today, loadRates(List.of(holding), today));
    }

    @Transactional
    public FixedIncomeHoldingResponse updateHolding(UUID id, FixedIncomeHoldingRequest request, UUID userId, LocalDate today) {
        FixedIncomeHolding holding = findHolding(id, userId);
        boolean hasRedemptions = redemptionRepository.existsByHoldingId(holding.getId());
        if (hasRedemptions && (holding.getPrincipal().compareTo(request.principal()) != 0
                || !holding.getAppliedAt().equals(request.appliedAt()))) {
            throw new BusinessRuleException("Valor e data da aplicação não podem mudar depois de um resgate.");
        }
        if (holding.getFundingTransferGroupId() != null && holding.getPrincipal().compareTo(request.principal()) != 0) {
            throw new BusinessRuleException("O valor da aplicação é o da transferência que a trouxe e não pode mudar.");
        }
        apply(holding, request, today);
        requestBackfillIfNeeded(holding.getAppliedAt());
        return toResponse(holding, today, loadRates(List.of(holding), today));
    }

    /**
     * Exclui só o cadastro. A transferência que trouxe o dinheiro fica: ela aconteceu, e o saldo
     * volta a aparecer como não alocado na conta.
     */
    @Transactional
    public void deleteHolding(UUID id, UUID userId) {
        FixedIncomeHolding holding = findHolding(id, userId);
        if (redemptionRepository.existsByHoldingId(holding.getId())) {
            throw new BusinessRuleException("Aplicação com resgate registrado não pode ser excluída.");
        }
        holding.setDeletedAt(Instant.now());
    }

    @Transactional
    public FixedIncomeHoldingResponse setManualValue(UUID id, ManualValueRequest request, UUID userId, LocalDate today) {
        FixedIncomeHolding holding = findActiveHolding(id, userId);
        if ((request.value() == null) != (request.date() == null)) {
            throw new BusinessRuleException("Informe o valor e a data juntos, ou nenhum dos dois para limpar.");
        }
        if (holding.getKind() == FixedIncomeKind.TESOURO_DIRETO && request.value() != null) {
            throw new BusinessRuleException("O Tesouro Direto usa o preço de mercado do dia; não há valor a informar.");
        }
        if (request.date() != null && (request.date().isBefore(holding.getAppliedAt()) || request.date().isAfter(today))) {
            throw new BusinessRuleException("A data do valor informado deve estar entre a aplicação e hoje.");
        }
        holding.setManualValue(request.value());
        holding.setManualValueDate(request.date());
        return toResponse(holding, today, loadRates(List.of(holding), today));
    }

    // ── Resgate ──────────────────────────────────────────────────────────────

    /** O que um resgate na data pagaria, sem gravar nada. */
    @Transactional(readOnly = true)
    public Valuation previewRedemption(UUID id, LocalDate date, boolean total, BigDecimal grossAmount,
                                       UUID userId, LocalDate today) {
        FixedIncomeHolding holding = findActiveHolding(id, userId);
        validateRedemptionDate(holding, date, today);
        Valuation valuation = calculator.value(holding, date, loadRates(List.of(holding), date));
        return valuation.portion(fraction(valuation, total, grossAmount));
    }

    @Transactional
    public FixedIncomeHoldingResponse redeem(UUID id, RedeemFixedIncomeRequest request, UUID userId, LocalDate today) {
        FixedIncomeHolding holding = findActiveHolding(id, userId);
        LocalDate date = request.date();
        validateRedemptionDate(holding, date, today);
        if (request.toAccountId() != null && request.transferGroupId() != null) {
            throw new BusinessRuleException("Informe a conta de destino ou a transferência existente, não as duas.");
        }

        Valuation full = calculator.value(holding, date, loadRates(List.of(holding), date));
        BigDecimal fraction = fraction(full, request.isTotal(), request.grossAmount());
        boolean total = fraction.compareTo(BigDecimal.ONE) >= 0;
        Valuation portion = full.portion(fraction);
        BigDecimal principalPortion = total ? holding.getRemainingPrincipal() : portion.principal();
        BigDecimal quantityPortion = holding.getTreasuryQuantity() == null ? null
                : total ? holding.getTreasuryQuantity()
                : holding.getTreasuryQuantity().multiply(fraction).setScale(8, RoundingMode.HALF_EVEN);

        UUID transferGroupId = null;
        BigDecimal net = request.netAmount() != null ? request.netAmount() : portion.net();
        if (request.transferGroupId() != null) {
            net = validateRedemptionTransfer(request.transferGroupId(), holding, userId);
            transferGroupId = request.transferGroupId();
        }

        FixedIncomeRedemption redemption = new FixedIncomeRedemption();
        redemption.setHolding(holding);
        redemption.setRedeemedAt(date);
        redemption.setPrincipalPortion(principalPortion);
        redemption.setTreasuryQuantity(quantityPortion);
        redemption.setGross(portion.gross());
        redemption.setIncomeTax(portion.incomeTax());
        redemption.setIof(portion.iof());
        redemption.setNet(net);
        redemption.setYieldTransactionId(recordYield(holding, date, net.subtract(principalPortion), userId));

        if (request.toAccountId() != null) {
            Account destination = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.toAccountId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.toAccountId()));
            List<Transaction> legs = accountService.transfer(holding.getAccount(), destination, net, date,
                    "Resgate · " + holding.getName());
            transferGroupId = legs.getFirst().getTransferGroupId();
        }
        redemption.setTransferGroupId(transferGroupId);
        redemptionRepository.save(redemption);

        holding.setRemainingPrincipal(holding.getRemainingPrincipal().subtract(principalPortion));
        if (quantityPortion != null) {
            holding.setTreasuryQuantity(holding.getTreasuryQuantity().subtract(quantityPortion));
        }
        if (holding.getManualValue() != null) {
            // O valor informado era do total; depois do resgate vale só para o que ficou.
            holding.setManualValue(total ? null : holding.getManualValue().multiply(BigDecimal.ONE.subtract(fraction))
                    .setScale(2, RoundingMode.HALF_EVEN));
            holding.setManualValueDate(total ? null : holding.getManualValueDate());
        }
        if (total) {
            holding.setStatus(FixedIncomeStatus.REDEEMED);
        }
        return toResponse(holding, today, loadRates(List.of(holding), today));
    }

    // ── Regras ───────────────────────────────────────────────────────────────

    /** Campos comuns a criação e edição, já validados contra o tipo. */
    private void apply(FixedIncomeHolding holding, FixedIncomeHoldingRequest request, LocalDate today) {
        FixedIncomeKind kind = holding.getKind();
        if (request.appliedAt().isAfter(today)) {
            throw new BusinessRuleException("A data da aplicação não pode estar no futuro.");
        }
        if (request.maturityDate() != null && !request.maturityDate().isAfter(request.appliedAt())) {
            throw new BusinessRuleException("O vencimento deve ser posterior à data da aplicação.");
        }
        FixedIncomeLiquidity liquidity = request.liquidity() != null ? request.liquidity()
                : request.maturityDate() == null ? FixedIncomeLiquidity.DAILY : FixedIncomeLiquidity.AT_MATURITY;
        if (liquidity == FixedIncomeLiquidity.AT_MATURITY && request.maturityDate() == null) {
            throw new BusinessRuleException("Aplicação com liquidez no vencimento precisa da data de vencimento.");
        }

        FixedIncomeIndexer indexer = request.indexer();
        BigDecimal rate = request.rate();
        String bondType = null;
        BigDecimal quantity = null;
        switch (kind) {
            case CDB, LCI, LCA -> {
                if (indexer == null || rate == null) {
                    throw new BusinessRuleException("Informe o indexador e a taxa da aplicação.");
                }
            }
            case OTHER -> {
                if (indexer != null && rate == null) {
                    throw new BusinessRuleException("Informe a taxa do indexador.");
                }
                if (indexer == null) {
                    rate = null;
                }
            }
            case TESOURO_DIRETO -> {
                if (request.treasuryBondType() == null || request.treasuryBondType().isBlank() || request.maturityDate() == null) {
                    throw new BusinessRuleException("Informe o título do Tesouro e o vencimento.");
                }
                indexer = null;
                rate = null;
                bondType = request.treasuryBondType().trim();
                quantity = request.treasuryQuantity() != null ? request.treasuryQuantity()
                        : deriveTreasuryQuantity(bondType, request.maturityDate(), request.appliedAt(), request.principal());
            }
        }

        holding.setIndexer(indexer);
        holding.setRate(rate);
        holding.setName(request.name().trim());
        holding.setIssuer(request.issuer() == null || request.issuer().isBlank() ? null : request.issuer().trim());
        holding.setTaxExempt(kind == FixedIncomeKind.OTHER ? Boolean.TRUE.equals(request.taxExempt()) : kind.isTaxExempt());
        holding.setLiquidity(liquidity);
        holding.setAppliedAt(request.appliedAt());
        holding.setMaturityDate(request.maturityDate());
        holding.setTreasuryBondType(bondType);
        holding.setTreasuryQuantity(quantity);
        holding.setNotes(request.notes());
        if (holding.getId() == null || holding.getPrincipal().compareTo(request.principal()) != 0) {
            holding.setPrincipal(request.principal());
            holding.setRemainingPrincipal(request.principal());
        }
    }

    private BigDecimal deriveTreasuryQuantity(String bondType, LocalDate maturity, LocalDate appliedAt, BigDecimal principal) {
        TreasuryBondPrice price = marketDataRepository.findTreasuryPrice(bondType, maturity, appliedAt)
                .filter(found -> found.puBuy().signum() > 0)
                .orElseThrow(() -> new BusinessRuleException(
                        "Sem cotação do título na data da aplicação; informe a quantidade de títulos."));
        return principal.divide(price.puBuy(), 8, RoundingMode.HALF_EVEN);
    }

    /** A transferência existente precisa ter entrado na conta da aplicação, com o mesmo valor. */
    private UUID validateFundingTransfer(UUID transferGroupId, FixedIncomeHolding holding, UUID userId) {
        Transaction inbound = transferLegs(transferGroupId, userId).stream()
                .filter(leg -> leg.getAccount().getId().equals(holding.getAccount().getId()) && leg.getAmount().signum() > 0)
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("A transferência não entrou na conta de investimento da aplicação."));
        if (inbound.getAmount().compareTo(holding.getPrincipal()) != 0) {
            throw new BusinessRuleException("O valor da aplicação deve ser o da transferência (" + inbound.getAmount() + ").");
        }
        return transferGroupId;
    }

    /** A transferência existente precisa ter saído da conta da aplicação; o valor dela é o líquido. */
    private BigDecimal validateRedemptionTransfer(UUID transferGroupId, FixedIncomeHolding holding, UUID userId) {
        if (redemptionRepository.existsByTransferGroupId(transferGroupId)) {
            throw new BusinessRuleException("Esta transferência já está vinculada a outro resgate.");
        }
        return transferLegs(transferGroupId, userId).stream()
                .filter(leg -> leg.getAccount().getId().equals(holding.getAccount().getId()) && leg.getAmount().signum() < 0)
                .findFirst()
                .map(leg -> leg.getAmount().negate())
                .orElseThrow(() -> new BusinessRuleException("A transferência não saiu da conta de investimento da aplicação."));
    }

    private List<Transaction> transferLegs(UUID transferGroupId, UUID userId) {
        List<Transaction> legs = transactionRepository.findAllByTransferGroupId(transferGroupId);
        if (legs.isEmpty() || legs.stream().anyMatch(leg -> !userId.equals(leg.getUserId()))) {
            throw new ResourceNotFoundException("Transfer not found: " + transferGroupId);
        }
        return legs;
    }

    /**
     * Rendimento (ou perda) do resgate como transação na conta de investimento, em
     * Investimentos › Rendimentos. É o que faz o saldo da conta voltar ao principal restante.
     */
    private UUID recordYield(FixedIncomeHolding holding, LocalDate date, BigDecimal amount, UUID userId) {
        if (amount.signum() == 0) {
            return null;
        }
        Category subcategory = categoryRepository.findSystemSubcategory(YIELD_CATEGORY, YIELD_SUBCATEGORY)
                .orElseThrow(() -> new IllegalStateException("Subcategoria do sistema ausente: "
                        + YIELD_CATEGORY + " › " + YIELD_SUBCATEGORY));
        boolean gain = amount.signum() > 0;
        return transactionService.createTransaction(new CreateTransactionRequest(
                holding.getAccount().getId(),
                gain ? TransactionType.INCOME : TransactionType.EXPENSE,
                amount.abs(),
                (gain ? "Rendimento · " : "Perda no resgate · ") + holding.getName(),
                date, date, null,
                subcategory.getParent().getId(), subcategory.getId(),
                null, null, TransactionStatus.PAID, null, null), userId).id();
    }

    private static void validateRedemptionDate(FixedIncomeHolding holding, LocalDate date, LocalDate today) {
        if (date.isBefore(holding.getAppliedAt()) || date.isAfter(today)) {
            throw new BusinessRuleException("A data do resgate deve estar entre a aplicação e hoje.");
        }
    }

    private static BigDecimal fraction(Valuation valuation, boolean total, BigDecimal grossAmount) {
        if (total) {
            return BigDecimal.ONE;
        }
        if (grossAmount == null) {
            throw new BusinessRuleException("Informe o valor bruto a resgatar ou marque o resgate total.");
        }
        if (grossAmount.compareTo(valuation.gross()) > 0) {
            throw new BusinessRuleException("O valor a resgatar passa do valor bruto da aplicação (" + valuation.gross() + ").");
        }
        return valuation.gross().signum() == 0 ? BigDecimal.ONE
                : grossAmount.divide(valuation.gross(), MathContext.DECIMAL64);
    }

    private void requestBackfillIfNeeded(LocalDate appliedAt) {
        boolean missing = marketDataRepository.findEarliestRateDate(MarketIndex.CDI)
                .map(appliedAt::isBefore)
                .orElse(false);
        if (missing) {
            eventPublisher.publishEvent(new MarketDataBackfillRequested(appliedAt));
        }
    }

    // ── Montagem ─────────────────────────────────────────────────────────────

    /** Índices e preços que as aplicações precisam, de uma vez, de {@code min(applied_at)} até {@code to}. */
    private MarketRates loadRates(Collection<FixedIncomeHolding> holdings, LocalDate to) {
        MarketRates rates = new MarketRates();
        if (holdings.isEmpty()) {
            return rates;
        }
        LocalDate from = holdings.stream().map(FixedIncomeHolding::getAppliedAt).min(Comparator.naturalOrder()).orElseThrow();
        for (MarketIndex index : MarketIndex.values()) {
            rates.addRates(marketDataRepository.findRates(index, index.isDaily() ? from : from.withDayOfMonth(1), to));
        }
        holdings.stream()
                .filter(holding -> holding.getKind() == FixedIncomeKind.TESOURO_DIRETO)
                .map(holding -> Map.entry(holding.getTreasuryBondType(), holding.getMaturityDate()))
                .distinct()
                .forEach(key -> rates.addTreasuryPrices(
                        marketDataRepository.findTreasuryPrices(key.getKey(), key.getValue(), from, to)));
        return rates;
    }

    private FixedIncomeHoldingResponse toResponse(FixedIncomeHolding holding, LocalDate today, MarketRates rates) {
        boolean active = holding.getStatus() == FixedIncomeStatus.ACTIVE;
        Valuation valuation = active ? calculator.value(holding, today, rates) : null;
        BigDecimal yieldPercent = null;
        BigDecimal cdiPercent = null;
        if (valuation != null && valuation.principal().signum() > 0) {
            BigDecimal holdingReturn = valuation.gross().divide(valuation.principal(), MathContext.DECIMAL64).subtract(BigDecimal.ONE);
            yieldPercent = holdingReturn.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN);
            double cdiReturn = calculator.cdiFactor(holding.getAppliedAt(), today, rates) - 1;
            if (cdiReturn > 0) {
                cdiPercent = holdingReturn.divide(BigDecimal.valueOf(cdiReturn), MathContext.DECIMAL64)
                        .movePointRight(2).setScale(1, RoundingMode.HALF_EVEN);
            }
        }
        Long daysToMaturity = holding.getMaturityDate() == null ? null
                : ChronoUnit.DAYS.between(today, holding.getMaturityDate());
        List<FixedIncomeRedemptionResponse> redemptions = holding.getId() == null ? List.of()
                : redemptionRepository.findAllByHoldingIdOrderByRedeemedAtAsc(holding.getId()).stream()
                .map(r -> new FixedIncomeRedemptionResponse(r.getId(), r.getRedeemedAt(), r.getPrincipalPortion(),
                        r.getTreasuryQuantity(), r.getGross(), r.getIncomeTax(), r.getIof(), r.getNet(),
                        r.getYieldTransactionId(), r.getTransferGroupId()))
                .toList();
        return new FixedIncomeHoldingResponse(holding.getId(), holding.getAccount().getId(), holding.getAccount().getName(),
                holding.getKind(), holding.getIndexer(), holding.getRate(), holding.getName(), holding.getIssuer(),
                holding.isTaxExempt(), holding.getLiquidity(), holding.getAppliedAt(), holding.getMaturityDate(),
                holding.getPrincipal(), holding.getRemainingPrincipal(), holding.getTreasuryBondType(),
                holding.getTreasuryQuantity(), holding.getManualValue(), holding.getManualValueDate(),
                holding.getStatus(), holding.getNotes(), valuation, yieldPercent, cdiPercent, daysToMaturity, redemptions);
    }

    private List<InvestmentSummaryResponse.AccountAllocation> accountAllocations(UUID userId, List<FixedIncomeHolding> active) {
        Map<UUID, BigDecimal> balances = new HashMap<>();
        for (Object[] row : transactionRepository.sumPaidAmountByAccountForUser(userId)) {
            balances.put((UUID) row[0], (BigDecimal) row[1]);
        }
        Map<UUID, BigDecimal> allocated = new HashMap<>();
        active.forEach(holding -> allocated.merge(holding.getAccount().getId(), holding.getRemainingPrincipal(), BigDecimal::add));
        return accountRepository.findAllByUserIdAndDeletedAtIsNullAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId)
                .stream()
                .filter(account -> account.getType() == AccountType.INVESTMENT)
                .map(account -> {
                    BigDecimal balance = balances.getOrDefault(account.getId(), BigDecimal.ZERO);
                    BigDecimal principal = allocated.getOrDefault(account.getId(), BigDecimal.ZERO);
                    return new InvestmentSummaryResponse.AccountAllocation(account.getId(), account.getName(),
                            balance, principal, balance.subtract(principal));
                })
                .toList();
    }

    private static List<InvestmentSummaryResponse.AllocationSlice> allocation(
            Map<FixedIncomeHolding, Valuation> valuations, Function<FixedIncomeHolding, String> key, BigDecimal totalGross) {
        Map<String, BigDecimal> grouped = new LinkedHashMap<>();
        valuations.forEach((holding, valuation) -> grouped.merge(key.apply(holding), valuation.gross(), BigDecimal::add));
        return grouped.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(entry -> new InvestmentSummaryResponse.AllocationSlice(entry.getKey(), entry.getValue(),
                        totalGross.signum() == 0 ? BigDecimal.ZERO
                                : entry.getValue().divide(totalGross, MathContext.DECIMAL64).movePointRight(2)
                                .setScale(1, RoundingMode.HALF_EVEN)))
                .toList();
    }

    private static String indexerKey(FixedIncomeHolding holding) {
        if (holding.getKind() == FixedIncomeKind.TESOURO_DIRETO) {
            return "MARKET";
        }
        return holding.getIndexer() == null ? "NONE" : holding.getIndexer().name();
    }

    private static String maturityBucket(FixedIncomeHolding holding, LocalDate today) {
        if (holding.getMaturityDate() == null || holding.getLiquidity() == FixedIncomeLiquidity.DAILY) {
            return "DAILY";
        }
        LocalDate maturity = holding.getMaturityDate();
        if (!maturity.isAfter(today.plusYears(1))) {
            return "UP_TO_1Y";
        }
        if (!maturity.isAfter(today.plusYears(3))) {
            return "UP_TO_3Y";
        }
        if (!maturity.isAfter(today.plusYears(5))) {
            return "UP_TO_5Y";
        }
        return "OVER_5Y";
    }

    private static BigDecimal initialTreasuryQuantity(FixedIncomeHolding holding, List<FixedIncomeRedemption> redemptions) {
        if (holding.getTreasuryQuantity() == null) {
            return null;
        }
        BigDecimal quantity = holding.getTreasuryQuantity();
        for (FixedIncomeRedemption redemption : redemptions) {
            if (redemption.getTreasuryQuantity() != null) {
                quantity = quantity.add(redemption.getTreasuryQuantity());
            }
        }
        return quantity;
    }

    private static BigDecimal sum(Collection<Valuation> valuations, Function<Valuation, BigDecimal> field) {
        return valuations.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private FixedIncomeHolding findHolding(UUID id, UUID userId) {
        return holdingRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Holding not found: " + id));
    }

    private FixedIncomeHolding findActiveHolding(UUID id, UUID userId) {
        FixedIncomeHolding holding = findHolding(id, userId);
        if (holding.getStatus() != FixedIncomeStatus.ACTIVE) {
            throw new BusinessRuleException("A aplicação já foi resgatada.");
        }
        return holding;
    }

    private Account findInvestmentAccount(UUID accountId, UUID userId) {
        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + accountId));
        if (account.getType() != AccountType.INVESTMENT) {
            throw new BusinessRuleException("A aplicação precisa ficar numa conta do tipo Investimento.");
        }
        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("A conta de investimento está arquivada.");
        }
        return account;
    }
}
