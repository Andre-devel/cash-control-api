package com.cashcontrol.api.service.openfinance;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.BankAccountKind;
import com.cashcontrol.api.domain.entity.BankConnection;
import com.cashcontrol.api.domain.entity.BankConnectionAccount;
import com.cashcontrol.api.domain.entity.BankProvider;
import com.cashcontrol.api.repository.BankConnectionAccountRepository;
import com.cashcontrol.api.repository.BankConnectionRepository;
import com.cashcontrol.api.service.push.PushService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Aviso diário por push quando uma conexão da Pluggy precisa de ação antes de faltar lançamento:
 * item em erro, sem dados novos há mais de {@link #STALE_AFTER}, ou consentimento do Open Finance
 * perto de vencer (nos marcos de {@link #CONSENT_WARNING_DAYS} dias) ou já vencido. Um push por
 * conexão, juntando os motivos.
 *
 * <p>A leitura das conexões é somente leitura; o envio de cada conexão roda em transação própria
 * ({@code REQUIRES_NEW}), porque {@link PushService#send} grava a última entrega e apaga o aparelho
 * que o serviço de push não conhece mais — dentro da transação somente leitura essas gravações se
 * perderiam, e a falha de uma conexão marcaria a leitura inteira para rollback.
 */
@Service
@Slf4j
public class BankConnectionAlertService {

    /** A Pluggy sincroniza o MeuPluggy uma vez por dia; 26 h dão folga para o horário variar. */
    static final Duration STALE_AFTER = Duration.ofHours(26);
    /** Dias antes do vencimento em que o consentimento é lembrado (a conferência é diária). */
    static final Set<Long> CONSENT_WARNING_DAYS = Set.of(15L, 7L, 1L);
    /** Status do item em que o banco parou de entregar dados até o usuário reconectar. */
    static final Set<String> RECONNECT_STATUSES = Set.of("LOGIN_ERROR", "OUTDATED", "WAITING_USER_INPUT");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM").withZone(ZONE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE);

    private final AppProperties appProperties;
    private final BankConnectionRepository connectionRepository;
    private final BankConnectionAccountRepository connectionAccountRepository;
    private final PushService pushService;
    private final TransactionTemplate newTransaction;

    public BankConnectionAlertService(AppProperties appProperties, BankConnectionRepository connectionRepository,
                                      BankConnectionAccountRepository connectionAccountRepository, PushService pushService,
                                      PlatformTransactionManager transactionManager) {
        this.appProperties = appProperties;
        this.connectionRepository = connectionRepository;
        this.connectionAccountRepository = connectionAccountRepository;
        this.pushService = pushService;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** @return quantas conexões geraram aviso entregue a pelo menos um aparelho */
    @Transactional(readOnly = true)
    public int checkAll(Instant now) {
        if (!appProperties.getPluggy().isEnabled()) {
            return 0;
        }
        int sent = 0;
        for (BankConnection connection : connectionRepository.findAllByProvider(BankProvider.PLUGGY)) {
            try {
                String payload = payload(connection, now);
                if (payload != null && deliver(connection, payload) > 0) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Bank connection alert failed for connection {}: {}", connection.getId(), e.toString());
            }
        }
        return sent;
    }

    private int deliver(BankConnection connection, String payload) {
        Integer delivered = newTransaction.execute(status -> pushService.send(connection.getUserId(), subscription -> payload));
        return delivered != null ? delivered : 0;
    }

    /** O push da conexão, ou {@code null} quando ela não precisa de nada. */
    private String payload(BankConnection connection, Instant now) {
        String name = displayName(connection);
        String title = null;
        List<String> body = new ArrayList<>();

        Instant lastData = connection.getLastSyncedAt() != null ? connection.getLastSyncedAt() : connection.getCreatedAt();
        if (RECONNECT_STATUSES.contains(connection.getStatus())) {
            title = name + ": precisa reconectar";
            body.add(connection.getStatusDetail() != null && !connection.getStatusDetail().isBlank()
                    ? connection.getStatusDetail()
                    : "O banco parou de enviar dados. Reconecte no Perfil.");
        } else if (lastData != null && Duration.between(lastData, now).compareTo(STALE_AFTER) > 0) {
            title = name + ": sem dados novos";
            body.add("Última atualização com o banco em " + DAY.format(lastData) + " às " + TIME.format(lastData)
                     + ". Confira a conexão no Perfil.");
        }

        Instant expiresAt = connection.getConsentExpiresAt();
        if (expiresAt != null) {
            // Dias de calendário em São Paulo: DAYS.between entre instantes trunca, e um vencimento
            // amanhã cedo (menos de 24 h) contaria como já vencido.
            long days = ChronoUnit.DAYS.between(now.atZone(ZONE).toLocalDate(), expiresAt.atZone(ZONE).toLocalDate());
            if (!expiresAt.isAfter(now)) {
                body.add("O consentimento venceu em " + DAY.format(expiresAt)
                         + ". Reconecte no Perfil para o app voltar a ler o banco.");
                title = Objects.requireNonNullElse(title, name + ": consentimento venceu");
            } else if (CONSENT_WARNING_DAYS.contains(days)) {
                body.add("O consentimento vence em " + DAY.format(expiresAt)
                         + ". Renove no Perfil para o app continuar lendo o banco.");
                title = Objects.requireNonNullElse(title,
                        name + ": consentimento vence em " + days + (days == 1 ? " dia" : " dias"));
            }
        }

        if (title == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "connection");
        payload.put("connectionId", connection.getId().toString());
        payload.put("title", title);
        payload.put("body", String.join(" ", body));
        return JSON.writeValueAsString(payload);
    }

    /**
     * Nome do conector mais o número da conta corrente: as duas conexões do MeuPluggy (PF e PJ)
     * chegam com o mesmo nome de conector.
     */
    private String displayName(BankConnection connection) {
        String connector = connection.getConnectorName() != null && !connection.getConnectorName().isBlank()
                ? connection.getConnectorName()
                : "Banco";
        return connectionAccountRepository.findAllByConnectionId(connection.getId()).stream()
                .filter(account -> account.getKind() == BankAccountKind.BANK)
                .map(BankConnectionAccount::getNumber)
                .filter(number -> number != null && !number.isBlank())
                .min(Comparator.naturalOrder())
                .map(number -> connector + " · " + number)
                .orElse(connector);
    }
}
