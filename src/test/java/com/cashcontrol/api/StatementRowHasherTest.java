package com.cashcontrol.api;

import com.cashcontrol.api.service.statement.ParsedStatementRow;
import com.cashcontrol.api.service.statement.StatementRowHasher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatementRowHasherTest {

    private final StatementRowHasher hasher = new StatementRowHasher();

    private ParsedStatementRow row(int lineNumber, String date, String history, String description, String amount) {
        return new ParsedStatementRow(lineNumber, LocalDate.parse(date), history, description, new BigDecimal(amount));
    }

    @Test
    void hash_fitsTheColumn() {
        List<String> hashes = hasher.hashAll(List.of(
                row(7, "2026-08-04", "Pix enviado ", "Pix Marketplace", "-144.06")));

        assertThat(hashes).singleElement().satisfies(h -> assertThat(h).hasSize(64));
    }

    @Test
    void hash_isStableAcrossReadsOfTheSameRow() {
        ParsedStatementRow first = row(7, "2026-08-04", "Pix enviado ", "Pix Marketplace", "-144.06");
        ParsedStatementRow sameRowInAnotherExport = row(120, "2026-08-04", "Pix enviado", "Pix Marketplace", "-144.06");

        // O número da linha muda de um export para outro e não pode entrar no hash.
        assertThat(hasher.hashAll(List.of(first)))
                .isEqualTo(hasher.hashAll(List.of(sameRowInAnotherExport)));
    }

    @Test
    void hash_differsWhenAnyFieldDiffers() {
        String base = hasher.hashAll(List.of(row(1, "2026-08-04", "Pix enviado", "Loja", "-10.00"))).get(0);

        assertThat(hasher.hashAll(List.of(row(1, "2026-08-05", "Pix enviado", "Loja", "-10.00"))).get(0))
                .isNotEqualTo(base);
        assertThat(hasher.hashAll(List.of(row(1, "2026-08-04", "Pix recebido", "Loja", "-10.00"))).get(0))
                .isNotEqualTo(base);
        assertThat(hasher.hashAll(List.of(row(1, "2026-08-04", "Pix enviado", "Outra Loja", "-10.00"))).get(0))
                .isNotEqualTo(base);
        assertThat(hasher.hashAll(List.of(row(1, "2026-08-04", "Pix enviado", "Loja", "-10.01"))).get(0))
                .isNotEqualTo(base);
        // Mesmo módulo, direção oposta: são lançamentos diferentes.
        assertThat(hasher.hashAll(List.of(row(1, "2026-08-04", "Pix enviado", "Loja", "10.00"))).get(0))
                .isNotEqualTo(base);
    }

    @Test
    void hash_distinguishesIdenticalRowsOnTheSameDay() {
        // Dois cafés de R$ 8,00 no mesmo dia são dois fatos. O dedup ingênuo por
        // data+valor+descrição jogaria um dos dois fora.
        List<String> hashes = hasher.hashAll(List.of(
                row(21, "2026-05-15", "Compra no débito", "Cafe Do Ponto", "-8.00"),
                row(22, "2026-05-15", "Compra no débito", "Cafe Do Ponto", "-8.00")));

        assertThat(hashes).doesNotHaveDuplicates();
    }

    @Test
    void hash_ordinalIsScopedToTheDay_soOverlappingExportsStillMatch() {
        // Export longo: a compra de 15/05 vem depois de outros dias.
        List<String> longExport = hasher.hashAll(List.of(
                row(7, "2026-06-01", "Pix enviado", "Alguem", "-5.00"),
                row(8, "2026-05-16", "Compra no débito", "Cafe Do Ponto", "-8.00"),
                row(9, "2026-05-15", "Compra no débito", "Cafe Do Ponto", "-8.00"),
                row(10, "2026-05-15", "Compra no débito", "Cafe Do Ponto", "-8.00")));

        // Export curto começando em 15/05: as mesmas duas linhas, sem o que veio antes.
        List<String> shortExport = hasher.hashAll(List.of(
                row(7, "2026-05-15", "Compra no débito", "Cafe Do Ponto", "-8.00"),
                row(8, "2026-05-15", "Compra no débito", "Cafe Do Ponto", "-8.00")));

        assertThat(shortExport).containsExactly(longExport.get(2), longExport.get(3));
    }

    @Test
    void hash_ignoresTrailingSpaceAndCaseInHistory() {
        assertThat(hasher.hashAll(List.of(row(1, "2026-08-04", "Pix enviado ", "Loja", "-10.00"))))
                .isEqualTo(hasher.hashAll(List.of(row(1, "2026-08-04", "PIX ENVIADO", "Loja", "-10.00"))));
    }

    /**
     * Garantia de compatibilidade: o parser passou a colapsar o enchimento de largura
     * fixa do extrato ("Loja A  Penapolis   Bra" → "Loja A Penapolis Bra"). Se o hash
     * mudasse junto, todo lançamento já importado voltaria a parecer novo e a
     * reimportação duplicaria a base inteira.
     */
    @Test
    void hash_isUnaffectedByTheFixedWidthPaddingOfTheStatement() {
        List<String> padded = hasher.hashAll(List.of(
                row(1, "2026-08-04", "Compra no débito", "Dias E Damasceno Ltda  Penapolis     Bra", "-70.00")));
        List<String> collapsed = hasher.hashAll(List.of(
                row(1, "2026-08-04", "Compra no débito", "Dias E Damasceno Ltda Penapolis Bra", "-70.00")));

        assertThat(collapsed).isEqualTo(padded);
    }

    @Test
    void hash_ignoresTrailingZerosInAmount() {
        assertThat(hasher.hashAll(List.of(row(1, "2026-08-04", "Pix enviado", "Loja", "-10.00"))))
                .isEqualTo(hasher.hashAll(List.of(row(1, "2026-08-04", "Pix enviado", "Loja", "-10.0"))));
    }
}
