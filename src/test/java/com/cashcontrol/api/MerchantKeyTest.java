package com.cashcontrol.api;

import com.cashcontrol.api.service.MerchantKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * As descrições vêm da fatura sintética de {@code FaturaPdfFixture} e do CSV do Inter em
 * {@code src/test/resources/fixtures/extrato-inter.csv} — nada inventado, porque o valor
 * do normalizador está inteiro em lidar com o que os arquivos de verdade trazem.
 *
 * <p>É o arquivo que vai crescer conforme aparecerem PDFs novos: quando uma fatura trouxer
 * um formato de descrição que quebra a memória, o caso entra aqui antes da correção.
 */
class MerchantKeyTest {

    /** Descrições reais, para os invariantes que valem para todas elas. */
    private static final List<String> REAL_DESCRIPTIONS = List.of(
            "LOJA DE TESTE (Parcela 04 de 05)",
            "ASSINATURA MENSAL",
            "PAGTO DEBITO AUTOMATICO",
            "OUTRA LOJA (Parcela 01 de 10)",
            "SHOPEE *LarkSpComercio (Parcela 04 de 05)",
            "Dias E Damasceno Ltda  Penapolis     Bra",
            "Comercio De Bebidas E Pesca Koga Ltda",
            "Inter Shop - Cashback extra - 173260007007647",
            "PORTOSEG SA C FINANC E INVEST",
            "Cdb Di Flut Tb Pernambucanas Financiadora S.a",
            "Cdb Porq Obj Banco Inter Sa",
            "Pix Marketplace");

    // ── O que a memória precisa que funcione ────────────────────────────────

    @Test
    void key_isTheSameAcrossInstallmentsOfOnePurchase() {
        // O caso que justifica a classe: as parcelas de uma compra chegam como descrições
        // diferentes, e sem colapsá-las nada aprendido em julho vale em agosto.
        assertThat(MerchantKey.of("SHOPEE *LarkSpComercio (Parcela 04 de 05)"))
                .isEqualTo(MerchantKey.of("SHOPEE *LarkSpComercio (Parcela 05 de 05)"))
                .isEqualTo("larkspcomercio");
    }

    @Test
    void key_ignoresCaseAccentsAndLayoutWhitespace() {
        // Caixa e espaçamento variam entre extrações do PDF; acento varia entre o extrato
        // e a fatura do mesmo comerciante.
        assertThat(MerchantKey.of("PADARIA SÃO JOÃO"))
                .isEqualTo(MerchantKey.of("  padaria   sao joao "))
                .isEqualTo("padaria sao joao");
    }

    @Test
    void key_dropsThePaymentGatewayPrefix() {
        // O gateway que processou a compra não é o comerciante, e o mesmo comerciante
        // aparece ora com prefixo, ora sem.
        assertThat(MerchantKey.of("PAG*Loja do Zé")).isEqualTo("loja do ze");
        assertThat(MerchantKey.of("MP*MERCADO NOVO")).isEqualTo("mercado novo");
        assertThat(MerchantKey.of("PP*ASSINATURA MENSAL")).isEqualTo("assinatura mensal");
        assertThat(MerchantKey.of("SHOPEE *LarkSpComercio")).isEqualTo("larkspcomercio");
    }

    @Test
    void key_keepsDescriptionsThatOnlyHappenToHaveAnAsterisk() {
        // O limite de tamanho do prefixo é o que impede a regra de comer a descrição toda.
        assertThat(MerchantKey.of("SUPERMERCADO BOM PRECO *FILIAL"))
                .isEqualTo("supermercado bom preco filial");
    }

    @Test
    void key_dropsIdentifiersGluedToTheDescription() {
        // O id do cashback muda a cada crédito; sem tirá-lo, cada linha vira um
        // comerciante diferente e a memória nunca acumula.
        assertThat(MerchantKey.of("Inter Shop - Cashback extra - 173260007007647"))
                .isEqualTo(MerchantKey.of("Inter Shop - Cashback extra - 998877665544332"))
                .isEqualTo("inter shop cashback extra");
    }

    @Test
    void key_keepsShortNumbersThatArePartOfTheName() {
        assertThat(MerchantKey.of("POSTO 24 HORAS")).isEqualTo("posto 24 horas");
    }

    @Test
    void key_dropsTheTrailingPlaceMarker() {
        // O emissor põe e tira UF e país sem critério entre um mês e outro.
        assertThat(MerchantKey.of("Dias E Damasceno Ltda  Penapolis     Bra"))
                .isEqualTo(MerchantKey.of("Dias E Damasceno Ltda Penapolis"))
                .isEqualTo("dias e damasceno ltda penapolis");
        assertThat(MerchantKey.of("MERCADO NOVO SAO PAULO SP BR")).isEqualTo("mercado novo sao paulo");
    }

    @Test
    void key_keepsATrailingWordThatOnlyLooksLikeAPlaceMarker() {
        // "Sa" de sociedade anônima não é UF — e nenhuma UF é sufixo comum de razão social.
        assertThat(MerchantKey.of("Cdb Porq Obj Banco Inter Sa")).isEqualTo("cdb porq obj banco inter sa");
    }

    // ── O lado conservador: separar é barato, juntar errado não ──────────────

    @Test
    void key_separatesDistinctMerchants() {
        assertThat(MerchantKey.of("LOJA DE TESTE")).isNotEqualTo(MerchantKey.of("OUTRA LOJA"));
        assertThat(MerchantKey.of("Pix Marketplace"))
                .isNotEqualTo(MerchantKey.of("Comercio De Bebidas E Pesca Koga Ltda"));
        // A cidade fica: separá-la do nome sem cadastro é chute, e ela é estável mês a mês.
        assertThat(MerchantKey.of("MERCADO NOVO PENAPOLIS"))
                .isNotEqualTo(MerchantKey.of("MERCADO NOVO SAO PAULO"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "---", "*", "1234567", "(Parcela 04 de 05)"})
    void key_isNullWhenNothingIdentifiableIsLeft(String description) {
        // null, e não string vazia: chave vazia agruparia lançamentos sem nada em comum.
        assertThat(MerchantKey.of(description)).isNull();
    }

    @Test
    void key_isNullForNullDescription() {
        assertThat(MerchantKey.of(null)).isNull();
    }

    // ── Invariantes que o resto do plano depende ────────────────────────────

    @Test
    void key_fitsTheColumn() {
        String longDescription = "SUPERMERCADO E ACOUGUE NOSSA SENHORA APARECIDA DA BOA VIAGEM LTDA ME";

        assertThat(MerchantKey.of(longDescription)).hasSizeLessThanOrEqualTo(64);
        REAL_DESCRIPTIONS.forEach(d -> assertThat(MerchantKey.of(d)).hasSizeLessThanOrEqualTo(64));
    }

    @Test
    void key_usesOnlyLowercaseAlphanumericsAndSingleSpaces() {
        // O alfabeto pobre é o que torna a normalização reproduzível no SQL do backfill:
        // depois dela não sobra pontuação nem caractere exótico do PDF para as duas
        // implementações discordarem sobre.
        REAL_DESCRIPTIONS.forEach(d ->
                assertThat(MerchantKey.of(d)).matches("[a-z0-9]+( [a-z0-9]+)*"));
    }

    @Test
    void key_isIdempotent() {
        // O backfill grava a chave e o callback da entidade a recalcula sobre a descrição;
        // se normalizar duas vezes mudasse o resultado, uma chave já gravada poderia
        // deixar de casar com a mesma linha reprocessada.
        REAL_DESCRIPTIONS.forEach(d -> {
            String key = MerchantKey.of(d);
            assertThat(MerchantKey.of(key)).isEqualTo(key);
        });
    }
}
