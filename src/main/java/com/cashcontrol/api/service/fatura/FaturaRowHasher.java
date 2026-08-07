package com.cashcontrol.api.service.fatura;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Calcula a chave de deduplicação de cada linha da fatura.
 *
 * <p>O problema é o mesmo do extrato, por um caminho diferente: a fatura do mês
 * é reimportada com frequência — o usuário baixa o PDF de novo, ou importa antes
 * e depois do fechamento. Sem chave estável, cada passada duplicaria a fatura.
 *
 * <p>A chave é o SHA-256 de
 * {@code cartão|data da compra|descrição normalizada|valor|parcela|#ordinal}.
 * Não reaproveita {@code StatementRowHasher} porque a identidade é outra: a
 * fatura não tem a coluna "Histórico" do extrato, e um payload com um campo
 * vazio a mais geraria hashes diferentes para o mesmo fato.
 *
 * <p>A posição da parcela é um campo próprio e o sufixo "(Parcela X de Y)" sai
 * da descrição. Parece redundante, mas é o que permite reconhecer uma parcela
 * <em>gerada</em> pela importação do mês anterior quando ela reaparece como
 * linha no PDF do mês seguinte: os dois lados produzem a mesma identidade sem
 * depender de como o PDF escreveu o sufixo. Sem separar, "Parcela 04 de 05" e
 * "Parcela 05 de 05" só se distinguiriam por dentro do texto livre.
 *
 * <p>O {@code ordinal} distingue linhas <em>idênticas</em> — duas compras do
 * mesmo valor no mesmo estabelecimento no mesmo dia são dois lançamentos, não
 * uma duplicata — e é contado dentro do grupo de linhas iguais da própria
 * fatura. Como o PDF de um mês sempre traz o mesmo conjunto de linhas, contar
 * dentro do arquivo é estável entre reimportações.
 */
@Component
public class FaturaRowHasher {

    private static final Pattern COLLAPSIBLE_WHITESPACE = Pattern.compile("\\s+");

    /** O mesmo sufixo que o parser lê para extrair a posição da parcela. */
    private static final Pattern INSTALLMENT_SUFFIX =
            Pattern.compile("\\(\\s*Parcela\\s+\\d+\\s+de\\s+\\d+\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * @param cardLast4 seção de cartão a que as linhas pertencem. Entra na identidade para
     *                  que uma compra igual no titular e no adicional não vire duplicata caso
     *                  os dois grupos sejam apontados para o mesmo cartão cadastrado
     * @return os hashes na mesma ordem das linhas recebidas
     */
    public List<String> hashAll(String cardLast4, List<ParsedFaturaRow> rows) {
        Map<String, Integer> seen = new HashMap<>();
        return rows.stream()
                .map(row -> {
                    String identity = identityOf(cardLast4, row.date(), row.description(),
                            row.signedAmount(), row.installmentNumber(), row.totalInstallments());
                    int ordinal = seen.merge(identity, 1, Integer::sum) - 1;
                    return sha256(identity + "|#" + ordinal);
                })
                .toList();
    }

    /**
     * A chave de uma parcela específica de uma compra parcelada.
     *
     * <p>Usada na confirmação para gerar as parcelas seguintes à que está no PDF: a
     * parcela 5 criada agora precisa nascer com a chave que a linha "Parcela 05 de 05"
     * vai produzir no PDF do mês que vem, ou a importação seguinte duplicaria.
     *
     * <p>O ordinal é sempre zero: uma parcela futura é gerada a partir de uma linha
     * que já sobreviveu à deduplicação, então não há irmã idêntica para desempatar.
     */
    public String hashInstallment(String cardLast4, LocalDate purchaseDate, String description,
                                  BigDecimal amount, int installmentNumber, int totalInstallments) {
        return sha256(identityOf(cardLast4, purchaseDate, description, amount,
                installmentNumber, totalInstallments) + "|#0");
    }

    /**
     * A descrição sem o "(Parcela X de Y)".
     *
     * <p>É a forma que vai para o lançamento: a posição da parcela vira coluna própria na
     * transação, e o sufixo do PDF descreveria errado toda parcela gerada além da que foi
     * lida — a parcela 5 herdaria "(Parcela 04 de 05)".
     */
    public String stripInstallmentSuffix(String description) {
        return INSTALLMENT_SUFFIX.matcher(description).replaceAll("").trim();
    }

    private String identityOf(String cardLast4, LocalDate date, String description, BigDecimal amount,
                              Integer installmentNumber, Integer totalInstallments) {
        return cardLast4
               + "|" + date
               + "|" + normalize(stripInstallmentSuffix(description))
               // Valor absoluto: só despesas são hasheadas, e assim o sinal deixa de ser
               // um detalhe que o chamador precisa acertar.
               + "|" + amount.abs().stripTrailingZeros().toPlainString()
               + "|" + (installmentNumber != null && totalInstallments != null
                        ? installmentNumber + "/" + totalInstallments
                        : "");
    }

    /**
     * Espaços da diagramação do PDF não são informação: a mesma linha pode sair com
     * um espaço a mais numa extração e a menos noutra, e isso não pode mudar a chave.
     */
    private String normalize(String value) {
        return COLLAPSIBLE_WHITESPACE.matcher(value.trim()).replaceAll(" ").toLowerCase();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 é obrigatório em toda JVM; se faltar, o ambiente está quebrado.
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        }
    }
}
