package com.cashcontrol.api.controller;

import com.cashcontrol.api.service.openfinance.BankConnectionService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Webhook da Pluggy. Público e sem assinatura (a Pluggy não assina), então o corpo só serve
 * de aviso: o serviço relê o item na Pluggy. A resposta sai na hora, porque a Pluggy considera
 * falha o que passar de 10 s.
 */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
public class PluggyWebhookController {

    private final BankConnectionService bankConnectionService;

    @PostMapping("/api/v1/webhooks/pluggy")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestBody PluggyEvent event) {
        Instant receivedAt = Instant.now();
        // Todo evento fica no log, para saber com que frequência e atraso a Pluggy avisa (etapa 6.0).
        // O corpo vem de fora sem assinatura: só campos conhecidos, limpos.
        log.info("Pluggy webhook received: event={} eventId={} itemId={} accountId={} triggeredBy={}",
                clean(event.event()), clean(event.eventId()), event.itemId(), clean(event.accountId()),
                clean(event.triggeredBy()));
        if (event.event() == null || event.itemId() == null) {
            return;
        }
        if (event.event().startsWith("item/")) {
            bankConnectionService.onItemEvent(event.itemId());
        } else if ("transactions/created".equals(event.event()) && clean(event.accountId()) != null) {
            // Por ora só mede o atraso; a fila de pendentes é a etapa 6A.
            bankConnectionService.onTransactionsCreated(event.itemId(), clean(event.accountId()),
                    event.transactionsCreatedAtFrom(), receivedAt);
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9/_.:-]", "");
        return cleaned.isEmpty() ? null : cleaned.substring(0, Math.min(cleaned.length(), 64));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PluggyEvent(String event, String eventId, UUID itemId, String accountId, String triggeredBy,
                       Instant transactionsCreatedAtFrom) {
    }
}
