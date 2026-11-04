package com.cashcontrol.api.service.openfinance;

/**
 * @author Andre
 * 04 de Novembro. de 2026
 */

import com.cashcontrol.api.service.openfinance.PluggyClient.Counterparty;
import com.cashcontrol.api.service.openfinance.PluggyClient.DocumentNumber;
import com.cashcontrol.api.service.openfinance.PluggyClient.Merchant;
import com.cashcontrol.api.service.openfinance.PluggyClient.Party;
import com.cashcontrol.api.service.openfinance.PluggyClient.PaymentData;
import com.cashcontrol.api.service.openfinance.PluggyClient.PluggyTransaction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PluggyCounterpartyTest {

    private static final Party HOLDER = new Party(null, new DocumentNumber("CPF", "***.111.222-**"),
            "1234567", "0001", "077", "00416968");
    private static final Party FULANO = new Party("Fulano de Tal", new DocumentNumber("CPF", "***.123.456-**"),
            "999", "0001", "260", "18236120");

    @Test
    void debitTakesTheReceiver() {
        PluggyTransaction tx = tx("-615.23", new PaymentData(HOLDER, FULANO, "PIX", null, "E123"), null);

        assertThat(PluggyTransaction.counterparty(tx))
                .isEqualTo(new Counterparty("Fulano de Tal", "***.123.456-**"));
    }

    @Test
    void creditTakesThePayer() {
        PluggyTransaction tx = tx("150.00", new PaymentData(FULANO, HOLDER, "PIX", null, "E456"), null);

        assertThat(PluggyTransaction.counterparty(tx))
                .isEqualTo(new Counterparty("Fulano de Tal", "***.123.456-**"));
    }

    @Test
    void withoutPartyNameFallsBackToMerchant() {
        Party unnamed = new Party(null, new DocumentNumber("CNPJ", "12.345.678/0001-90"), null, null, null, null);
        PluggyTransaction tx = tx("-42.90", new PaymentData(HOLDER, unnamed, "PIX", null, null),
                new Merchant("Padaria Sao Joao LTDA", "12345678000190", "1091101", "Food"));

        assertThat(PluggyTransaction.counterparty(tx))
                .isEqualTo(new Counterparty("Padaria Sao Joao LTDA", "12.345.678/0001-90"));
    }

    @Test
    void onlyMerchantWithoutPaymentData() {
        PluggyTransaction tx = tx("-42.90", null, new Merchant("Padaria Sao Joao LTDA", null, null, null));

        assertThat(PluggyTransaction.counterparty(tx)).isEqualTo(new Counterparty("Padaria Sao Joao LTDA", null));
    }

    @Test
    void nothingKnownIsNone() {
        assertThat(PluggyTransaction.counterparty(tx("-10.00", null, null))).isEqualTo(Counterparty.NONE);
        Party empty = new Party(" ", null, null, null, null, null);
        assertThat(PluggyTransaction.counterparty(tx("-10.00", new PaymentData(HOLDER, empty, null, null, null), null)))
                .isEqualTo(Counterparty.NONE);
    }

    @Test
    void parsesTheCheckingAccountPayload() {
        String json = """
                {"id":"tx-1","description":"Pix enviado - Fulano","amount":-615.23,"type":"DEBIT",
                 "status":"POSTED","date":"2026-11-03T15:00:00.000Z","currencyCode":"BRL",
                 "paymentData":{"payer":{"name":null,"documentNumber":{"type":"CPF","value":"***.111.222-**"},
                   "accountNumber":"1234567","branchNumber":"0001","routingNumber":"077","routingNumberISPB":"00416968"},
                   "receiver":{"name":"Fulano de Tal","documentNumber":{"type":"CPF","value":"***.123.456-**"},
                   "accountNumber":"999","branchNumber":"0001","routingNumber":"260","routingNumberISPB":"18236120"},
                   "paymentMethod":"PIX","reason":null,"referenceNumber":"E123","receiverReferenceId":null},
                 "merchant":{"name":"X","businessName":"Fulano ME","cnpj":"123","cnae":"4","category":"Y"}}
                """;

        PluggyTransaction tx = JsonMapper.builder().build().readValue(json, PluggyTransaction.class);

        assertThat(tx.paymentData().paymentMethod()).isEqualTo("PIX");
        assertThat(tx.merchant().businessName()).isEqualTo("Fulano ME");
        assertThat(PluggyTransaction.counterparty(tx))
                .isEqualTo(new Counterparty("Fulano de Tal", "***.123.456-**"));
    }

    private static PluggyTransaction tx(String amount, PaymentData paymentData, Merchant merchant) {
        BigDecimal value = new BigDecimal(amount);
        return new PluggyTransaction("tx-1", "Pix", value, value.signum() < 0 ? "DEBIT" : "CREDIT", "POSTED",
                Instant.parse("2026-11-03T15:00:00Z"), null, null, paymentData, merchant);
    }
}
