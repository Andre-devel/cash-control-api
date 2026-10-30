package com.cashcontrol.api.controller;

import com.cashcontrol.api.service.openfinance.BankConnectionService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Webhook da Pluggy. Público e sem assinatura (a Pluggy não assina), então o corpo só serve
 * de aviso: o serviço relê o item na Pluggy. A resposta sai na hora, porque a Pluggy considera
 * falha o que passar de 10 s.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class PluggyWebhookController {

    private final BankConnectionService bankConnectionService;

    @PostMapping("/api/v1/webhooks/pluggy")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestBody PluggyEvent event) {
        // transactions/* entram na sincronização automática (etapa 6); por ora só o item importa.
        if (event.event() != null && event.event().startsWith("item/") && event.itemId() != null) {
            bankConnectionService.onItemEvent(event.itemId());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PluggyEvent(String event, UUID itemId) {
    }
}
