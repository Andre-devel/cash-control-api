package com.cashcontrol.api;

import com.cashcontrol.api.service.fatura.FaturaRowHasher;
import com.cashcontrol.api.service.fatura.FaturaRowHasher.RowKey;
import com.cashcontrol.api.service.fatura.ParsedFaturaRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FaturaRowHasherTest {

    private final FaturaRowHasher hasher = new FaturaRowHasher();

    private ParsedFaturaRow row(int lineNumber, String date, String description, String amount) {
        return new ParsedFaturaRow(lineNumber, LocalDate.parse(date), description,
                new BigDecimal(amount), null, null);
    }

    private ParsedFaturaRow installment(int lineNumber, String date, String description, String amount,
                                        int number, int total) {
        return new ParsedFaturaRow(lineNumber, LocalDate.parse(date), description,
                new BigDecimal(amount), number, total);
    }

    private List<String> refs(String cardLast4, List<ParsedFaturaRow> rows) {
        return hasher.hashAll(cardLast4, rows).stream().map(RowKey::externalRef).toList();
    }

    private String ref(String cardLast4, ParsedFaturaRow row) {
        return refs(cardLast4, List.of(row)).getFirst();
    }

    @Test
    void hash_fitsTheColumn() {
        assertThat(ref("7866", row(59, "2026-04-04", "SHOPEE *LarkSpComercio", "-55.19"))).hasSize(64);
    }

    @Test
    void hash_isStableAcrossReadsOfTheSameRow() {
        // O número da linha muda quando o PDF é reprocessado — a fatura pode vir com uma
        // página a mais de propaganda — e não pode entrar no hash. Espaços da diagramação
        // também não.
        ParsedFaturaRow first = row(59, "2026-04-04", "SHOPEE *LarkSpComercio", "-55.19");
        ParsedFaturaRow sameRowLaterInTheFile = row(120, "2026-04-04", "SHOPEE  *LarkSpComercio ", "-55.19");

        assertThat(ref("7866", first)).isEqualTo(ref("7866", sameRowLaterInTheFile));
    }

    @Test
    void hash_differsWhenAnyFieldDiffers() {
        String base = ref("7866", row(1, "2026-04-04", "Loja", "-10.00"));

        assertThat(ref("7866", row(1, "2026-04-05", "Loja", "-10.00"))).isNotEqualTo(base);
        assertThat(ref("7866", row(1, "2026-04-04", "Outra Loja", "-10.00"))).isNotEqualTo(base);
        assertThat(ref("7866", row(1, "2026-04-04", "Loja", "-10.01"))).isNotEqualTo(base);
        assertThat(ref("7866", installment(1, "2026-04-04", "Loja", "-10.00", 1, 2))).isNotEqualTo(base);
    }

    @Test
    void hash_differsBetweenCardSections() {
        // Titular e adicional podem ter a mesma compra no mesmo dia. Se o usuário apontar
        // os dois grupos para o mesmo cartão cadastrado, a segunda não pode sumir como
        // duplicata da primeira.
        assertThat(ref("7866", row(1, "2026-04-04", "Loja", "-10.00")))
                .isNotEqualTo(ref("4776", row(1, "2026-04-04", "Loja", "-10.00")));
    }

    @Test
    void hash_distinguishesIdenticalRowsInTheSameFatura() {
        // Duas compras iguais no mesmo dia são dois lançamentos, não uma duplicata.
        List<String> hashes = refs("7866", List.of(
                row(1, "2026-07-15", "ANTHROPIC* CLAUDE SUB", "-110.00"),
                row(2, "2026-07-15", "ANTHROPIC* CLAUDE SUB", "-110.00")));

        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void hash_ofRepeatedRows_isStableWhenTheFaturaIsReadAgain() {
        List<ParsedFaturaRow> rows = List.of(
                row(1, "2026-07-15", "ANTHROPIC* CLAUDE SUB", "-110.00"),
                row(2, "2026-07-15", "ANTHROPIC* CLAUDE SUB", "-110.00"));

        // Reimportar o mesmo PDF precisa produzir exatamente os mesmos dois hashes, ou o
        // par voltaria a entrar.
        assertThat(refs("7866", rows)).isEqualTo(refs("7866", rows));
    }

    @Test
    void hash_takesTheInstallmentPositionFromTheMetadata_notFromTheDescription() {
        // O sufixo sai da descrição e a posição vira campo próprio, então escrever
        // "(Parcela 04 de 05)" ou não escrever dá no mesmo desde que a posição bata.
        ParsedFaturaRow spelledOut = installment(1, "2026-04-04", "Loja (Parcela 04 de 05)", "-55.19", 4, 5);
        ParsedFaturaRow bare = installment(1, "2026-04-04", "Loja", "-55.19", 4, 5);

        assertThat(ref("7866", spelledOut)).isEqualTo(ref("7866", bare));
    }

    @Test
    void hash_separatesTheInstallmentsOfTheSamePurchase() {
        // Cada parcela é um lançamento próprio. Se colidissem, gerar a parcela 5 junto com
        // a 4 seria bloqueado como duplicata dela mesma.
        List<String> hashes = refs("7866", List.of(
                installment(1, "2026-04-04", "Loja", "-55.19", 4, 5),
                installment(2, "2026-04-04", "Loja", "-55.19", 5, 5)));

        assertThat(hashes).doesNotHaveDuplicates();
    }

    /**
     * O emissor deixa o resto da divisão na primeira parcela: a fatura traz 48,28 e depois
     * 48,26. Se o valor entrasse na identidade, a parcela 2 estimada hoje e a linha
     * "Parcela 02 de 03" do mês que vem nunca se encontrariam, e a compra duplicaria.
     */
    @Test
    void hash_ofAnInstallment_ignoresTheAmount() {
        assertThat(ref("7866", installment(1, "2026-03-30", "EBN *TikTok Shop", "-48.28", 2, 3)))
                .isEqualTo(ref("7866", installment(9, "2026-03-30", "EBN *TikTok Shop", "-48.26", 2, 3)));
    }

    /**
     * O contrapeso de tirar o valor da identidade: duas compras parceladas iguais no mesmo
     * dia passam a se distinguir só pela ordem no arquivo. Elas existem de verdade — a
     * fatura de abril/2026 tem duas do mesmo estabelecimento, de R$ 85,11 e R$ 70,74.
     */
    @Test
    void hash_separatesTwoParceledPurchasesThatOnlyDifferByAmount() {
        List<RowKey> keys = hasher.hashAll("7866", List.of(
                installment(1, "2026-03-02", "MERCADOLIVRE*2PRODUTO", "-85.11", 2, 3),
                installment(2, "2026-03-02", "MERCADOLIVRE*2PRODUTO", "-70.74", 2, 3)));

        assertThat(keys).extracting(RowKey::externalRef).doesNotHaveDuplicates();
        assertThat(keys).extracting(RowKey::ordinal).containsExactly(0, 1);
    }

    /**
     * A garantia que sustenta a geração das parcelas futuras: a parcela 5 criada hoje a
     * partir da linha "Parcela 04 de 05" nasce com a mesma chave que a linha "Parcela 05
     * de 05" vai produzir no PDF do mês que vem. Sem isso, a importação seguinte
     * duplicaria toda compra parcelada.
     */
    @Test
    void hashInstallment_matchesTheRowThatTheNextFaturaWillBring() {
        String generatedNow = hasher.hashInstallment("7866", LocalDate.parse("2026-04-04"),
                "SHOPEE *LarkSpComercio (Parcela 04 de 05)", 5, 5, 0);

        String readNextMonth = ref("7866", installment(
                12, "2026-04-04", "SHOPEE *LarkSpComercio (Parcela 05 de 05)", "-55.19", 5, 5));

        assertThat(generatedNow).isEqualTo(readNextMonth);
    }

    /** E continua batendo quando o emissor cobra alguns centavos a menos na parcela. */
    @Test
    void hashInstallment_matchesEvenWhenTheNextInstallmentCostsLess() {
        String generatedNow = hasher.hashInstallment("7866", LocalDate.parse("2026-03-30"),
                "EBN *TikTok Shop (Parcela 01 de 03)", 2, 3, 0);

        String readNextMonth = ref("7866", installment(
                12, "2026-03-30", "EBN *TikTok Shop (Parcela 02 de 03)", "-48.26", 2, 3));

        assertThat(generatedNow).isEqualTo(readNextMonth);
    }

    @Test
    void hashInstallment_usesTheOrdinalOfTheRowItCameFrom() {
        String fromFirst = hasher.hashInstallment("7866", LocalDate.parse("2026-03-02"),
                "MERCADOLIVRE*2PRODUTO (Parcela 02 de 03)", 3, 3, 0);
        String fromSecond = hasher.hashInstallment("7866", LocalDate.parse("2026-03-02"),
                "MERCADOLIVRE*2PRODUTO (Parcela 02 de 03)", 3, 3, 1);

        assertThat(fromFirst).isNotEqualTo(fromSecond);
    }

    @Test
    void stripInstallmentSuffix_leavesTheDescriptionThatGoesToTheTransaction() {
        assertThat(hasher.stripInstallmentSuffix("SHOPEE *LarkSpComercio (Parcela 04 de 05)"))
                .isEqualTo("SHOPEE *LarkSpComercio");
        assertThat(hasher.stripInstallmentSuffix("ANTHROPIC* CLAUDE SUB"))
                .isEqualTo("ANTHROPIC* CLAUDE SUB");
    }

    @Test
    void normalizedDescription_isTheFormThatGroupsTheInstallmentsOfAPurchase() {
        assertThat(hasher.normalizedDescription("SHOPEE  *LarkSpComercio (Parcela 04 de 05) "))
                .isEqualTo("shopee *larkspcomercio");
    }
}
