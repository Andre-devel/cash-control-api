package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.service.MerchantKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trava a equivalência entre {@link MerchantKey#of(String)} e a expressão SQL do backfill
 * de {@code V26__add_merchant_key_to_transactions.sql}. As duas normalizações precisam
 * concordar sempre — se divergirem, o histórico gravado pela migração fica com chave
 * diferente da que o callback {@code @PrePersist}/{@code @PreUpdate} da entidade
 * {@code Transaction} vai calcular dali para a frente, e a memória de categorização
 * fica silenciosamente furada nas linhas antigas.
 *
 * <p>{@link #BACKFILL_EXPRESSION} é a mesma expressão da migração, passo a passo — mudou
 * uma, muda a outra.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class MerchantKeyBackfillIntegrationTest {

    /** Cópia literal da expressão de V26__add_merchant_key_to_transactions.sql. */
    private static final String BACKFILL_EXPRESSION = """
            SELECT nullif(
                regexp_replace(
                    rtrim(left(
                        btrim(regexp_replace(
                            regexp_replace(
                                regexp_replace(
                                    lower(unaccent(
                                        btrim(regexp_replace(
                                            ?,
                                            '\\(\\s*parcela\\s+\\d+\\s+de\\s+\\d+\\s*\\)',
                                            '',
                                            'gi'
                                        ))
                                    )),
                                    '^[a-z0-9]{1,9}[\\s ]*\\*[\\s ]*',
                                    ''
                                ),
                                '[0-9]{3,}',
                                ' ',
                                'g'
                            ),
                            '[^a-z0-9]+',
                            ' ',
                            'g'
                        )),
                    64)),
                    '(\\s+(ac|al|ap|am|ba|ce|df|es|go|ma|mt|ms|mg|pa|pb|pr|pe|pi|rj|rn|rs|ro|rr|sc|sp|se|to|br|bra))+$',
                    ''
                ),
                ''
            )
            """;

    /** As mesmas descrições reais de {@code MerchantKeyTest}, tiradas de fixtures de fatura. */
    private static final List<String> REAL_DESCRIPTIONS = List.of(
            "LOJA DE TESTE (Parcela 04 de 05)",
            "ASSINATURA MENSAL",
            "PAGTO DEBITO AUTOMATICO",
            "OUTRA LOJA (Parcela 01 de 10)",
            "SHOPEE *LarkSpComercio (Parcela 04 de 05)",
            "SHOPEE *LarkSpComercio (Parcela 05 de 05)",
            "Dias E Damasceno Ltda  Penapolis     Bra",
            "Comercio De Bebidas E Pesca Koga Ltda",
            "Inter Shop - Cashback extra - 173260007007647",
            "PORTOSEG SA C FINANC E INVEST",
            "Cdb Di Flut Tb Pernambucanas Financiadora S.a",
            "Cdb Porq Obj Banco Inter Sa",
            "Pix Marketplace",
            "PADARIA SÃO JOÃO",
            "  padaria   sao joao ",
            "PAG*Loja do Zé",
            "MP*MERCADO NOVO",
            "PP*ASSINATURA MENSAL",
            "SUPERMERCADO BOM PRECO *FILIAL",
            "POSTO 24 HORAS",
            "MERCADO NOVO SAO PAULO SP BR",
            "SUPERMERCADO E ACOUGUE NOSSA SENHORA APARECIDA DA BOA VIAGEM LTDA ME");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void sqlBackfillExpression_matchesMerchantKeyOf_forRealDescriptions() {
        for (String description : REAL_DESCRIPTIONS) {
            assertThat(sqlMerchantKey(description))
                    .as("descrição: %s", description)
                    .isEqualTo(MerchantKey.of(description));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "---", "*", "1234567", "(Parcela 04 de 05)"})
    void sqlBackfillExpression_isNullWhenNothingIdentifiableIsLeft(String description) {
        assertThat(sqlMerchantKey(description)).isNull();
        assertThat(MerchantKey.of(description)).isNull();
    }

    private String sqlMerchantKey(String description) {
        return jdbcTemplate.queryForObject(BACKFILL_EXPRESSION, String.class, description);
    }
}
