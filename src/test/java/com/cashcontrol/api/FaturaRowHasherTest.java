package com.cashcontrol.api;

import com.cashcontrol.api.service.fatura.FaturaRowHasher;
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

    @Test
    void hash_fitsTheColumn() {
        List<String> hashes = hasher.hashAll("7866", List.of(
                row(59, "2026-04-04", "SHOPEE *LarkSpComercio (Parcela 04 de 05)", "-55.19")));

        assertThat(hashes).singleElement().satisfies(hash -> assertThat(hash).hasSize(64));
    }

    @Test
    void hash_isStableAcrossReadsOfTheSameRow() {
        // O número da linha muda quando o PDF é reprocessado — a fatura pode vir com uma
        // página a mais de propaganda — e não pode entrar no hash. Espaços da diagramação
        // também não.
        ParsedFaturaRow first = row(59, "2026-04-04", "SHOPEE *LarkSpComercio", "-55.19");
        ParsedFaturaRow sameRowLaterInTheFile = row(120, "2026-04-04", "SHOPEE  *LarkSpComercio ", "-55.19");

        assertThat(hasher.hashAll("7866", List.of(first)))
                .isEqualTo(hasher.hashAll("7866", List.of(sameRowLaterInTheFile)));
    }

    @Test
    void hash_differsWhenAnyFieldDiffers() {
        String base = hasher.hashAll("7866", List.of(row(1, "2026-04-04", "Loja", "-10.00"))).getFirst();

        assertThat(hasher.hashAll("7866", List.of(row(1, "2026-04-05", "Loja", "-10.00"))).getFirst())
                .isNotEqualTo(base);
        assertThat(hasher.hashAll("7866", List.of(row(1, "2026-04-04", "Outra Loja", "-10.00"))).getFirst())
                .isNotEqualTo(base);
        assertThat(hasher.hashAll("7866", List.of(row(1, "2026-04-04", "Loja", "-10.01"))).getFirst())
                .isNotEqualTo(base);
        assertThat(hasher.hashAll("7866", List.of(installment(1, "2026-04-04", "Loja", "-10.00", 1, 2)))
                .getFirst()).isNotEqualTo(base);
    }

    @Test
    void hash_differsBetweenCardSections() {
        // Titular e adicional podem ter a mesma compra no mesmo dia. Se o usuário apontar
        // os dois grupos para o mesmo cartão cadastrado, a segunda não pode sumir como
        // duplicata da primeira.
        List<String> holder = hasher.hashAll("7866", List.of(row(1, "2026-04-04", "Loja", "-10.00")));
        List<String> additional = hasher.hashAll("4776", List.of(row(1, "2026-04-04", "Loja", "-10.00")));

        assertThat(holder).isNotEqualTo(additional);
    }

    @Test
    void hash_distinguishesIdenticalRowsInTheSameFatura() {
        // Duas compras iguais no mesmo dia são dois lançamentos, não uma duplicata.
        List<String> hashes = hasher.hashAll("7866", List.of(
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
        assertThat(hasher.hashAll("7866", rows)).isEqualTo(hasher.hashAll("7866", rows));
    }

    @Test
    void hash_takesTheInstallmentPositionFromTheMetadata_notFromTheDescription() {
        // O sufixo sai da descrição e a posição vira campo próprio, então escrever
        // "(Parcela 04 de 05)" ou não escrever dá no mesmo desde que a posição bata.
        ParsedFaturaRow spelledOut = installment(1, "2026-04-04", "Loja (Parcela 04 de 05)", "-55.19", 4, 5);
        ParsedFaturaRow bare = installment(1, "2026-04-04", "Loja", "-55.19", 4, 5);

        assertThat(hasher.hashAll("7866", List.of(spelledOut)))
                .isEqualTo(hasher.hashAll("7866", List.of(bare)));
    }

    @Test
    void hash_separatesTheInstallmentsOfTheSamePurchase() {
        // Cada parcela é um lançamento em uma fatura diferente. Se colidissem, gerar a
        // parcela 5 junto com a 4 seria bloqueado como duplicata dela mesma.
        List<String> hashes = hasher.hashAll("7866", List.of(
                installment(1, "2026-04-04", "Loja", "-55.19", 4, 5),
                installment(2, "2026-04-04", "Loja", "-55.19", 5, 5)));

        assertThat(hashes).doesNotHaveDuplicates();
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
                "SHOPEE *LarkSpComercio (Parcela 04 de 05)", new BigDecimal("55.19"), 5, 5);

        String readNextMonth = hasher.hashAll("7866", List.of(installment(
                12, "2026-04-04", "SHOPEE *LarkSpComercio (Parcela 05 de 05)", "-55.19", 5, 5)))
                .getFirst();

        assertThat(generatedNow).isEqualTo(readNextMonth);
    }

    @Test
    void stripInstallmentSuffix_leavesTheDescriptionThatGoesToTheTransaction() {
        assertThat(hasher.stripInstallmentSuffix("SHOPEE *LarkSpComercio (Parcela 04 de 05)"))
                .isEqualTo("SHOPEE *LarkSpComercio");
        assertThat(hasher.stripInstallmentSuffix("ANTHROPIC* CLAUDE SUB"))
                .isEqualTo("ANTHROPIC* CLAUDE SUB");
    }
}
