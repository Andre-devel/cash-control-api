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
 * <p><strong>O valor entra na identidade só nas compras à vista.</strong> Numa
 * compra parcelada ele varia entre as parcelas: o emissor deixa o resto da
 * divisão na primeira, e o que o PDF traz é 48,28 + 48,26 + 48,26. A parcela
 * gerada hoje precisa nascer com a chave que a linha do mês que vem vai
 * produzir — e essa linha vem com o valor <em>dela</em>. Amarrar a identidade ao
 * valor faria as duas nunca se encontrarem, e toda compra parcelada com centavos
 * de sobra duplicaria em silêncio na importação seguinte.
 *
 * <p>O {@code ordinal} distingue linhas <em>idênticas</em> — duas compras do
 * mesmo valor no mesmo estabelecimento no mesmo dia são dois lançamentos, não
 * uma duplicata — e é contado dentro do grupo de linhas iguais da própria
 * fatura. Como o PDF de um mês sempre traz o mesmo conjunto de linhas, contar
 * dentro do arquivo é estável entre reimportações. Ele é o preço de tirar o
 * valor da identidade: duas compras parceladas no mesmo dia, no mesmo lugar e
 * com o mesmo número de parcelas passam a se distinguir só pela ordem no
 * arquivo. É por isso que o ordinal acompanha a linha até a confirmação em vez
 * de ser recontado lá — a confirmação recebe só as linhas que o usuário marcou,
 * e recontar sobre um subconjunto daria outro número.
 */
@Component
public class FaturaRowHasher {

    private static final Pattern COLLAPSIBLE_WHITESPACE = Pattern.compile("\\s+");

    /** O mesmo sufixo que o parser lê para extrair a posição da parcela. */
    private static final Pattern INSTALLMENT_SUFFIX =
            Pattern.compile("\\(\\s*Parcela\\s+\\d+\\s+de\\s+\\d+\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * A chave de uma linha e a posição dela dentro do grupo de linhas iguais do arquivo.
     *
     * @param externalRef a chave de deduplicação
     * @param ordinal     0 para a primeira ocorrência da identidade, 1 para a segunda, e assim
     *                    por diante. Viaja para a confirmação junto com a linha porque é ele
     *                    que dá a chave certa às parcelas geradas a partir dela
     */
    public record RowKey(String externalRef, int ordinal) {}

    /**
     * @param cardLast4 seção de cartão a que as linhas pertencem. Entra na identidade para
     *                  que uma compra igual no titular e no adicional não vire duplicata caso
     *                  os dois grupos sejam apontados para o mesmo cartão cadastrado
     * @return as chaves na mesma ordem das linhas recebidas
     */
    public List<RowKey> hashAll(String cardLast4, List<ParsedFaturaRow> rows) {
        Map<String, Integer> seen = new HashMap<>();
        return rows.stream()
                .map(row -> {
                    String identity = identityOf(cardLast4, row.date(), row.description(),
                            row.signedAmount(), row.installmentNumber(), row.totalInstallments());
                    int ordinal = seen.merge(identity, 1, Integer::sum) - 1;
                    return new RowKey(sha256(identity + "|#" + ordinal), ordinal);
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
     * <p>Não recebe valor: a parcela do mês que vem virá com o valor dela, e a identidade
     * de uma compra parcelada não olha o valor justamente por isso.
     *
     * @param ordinal o ordinal da linha de origem, para que duas compras indistinguíveis
     *                no mesmo dia gerem parcelas com chaves diferentes
     */
    public String hashInstallment(String cardLast4, LocalDate purchaseDate, String description,
                                  int installmentNumber, int totalInstallments, int ordinal) {
        return sha256(identityOf(cardLast4, purchaseDate, description, null,
                installmentNumber, totalInstallments) + "|#" + ordinal);
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

    /**
     * A descrição como ela entra na identidade: sem o sufixo de parcela, sem os espaços da
     * diagramação e em caixa baixa.
     *
     * <p>Pública porque a confirmação agrupa as linhas da mesma compra por essa forma —
     * e agrupar por uma normalização diferente da que gera a chave separaria linhas que a
     * chave considera irmãs.
     */
    public String normalizedDescription(String description) {
        return normalize(stripInstallmentSuffix(description));
    }

    /**
     * @param amount ignorado quando a linha é parcelada; obrigatório quando não é
     */
    private String identityOf(String cardLast4, LocalDate date, String description, BigDecimal amount,
                              Integer installmentNumber, Integer totalInstallments) {
        boolean parceled = installmentNumber != null && totalInstallments != null;
        return cardLast4
               + "|" + date
               + "|" + normalize(stripInstallmentSuffix(description))
               // Valor absoluto: só despesas são hasheadas, e assim o sinal deixa de ser
               // um detalhe que o chamador precisa acertar.
               + "|" + (parceled ? "" : amount.abs().stripTrailingZeros().toPlainString())
               + "|" + (parceled ? installmentNumber + "/" + totalInstallments : "");
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
