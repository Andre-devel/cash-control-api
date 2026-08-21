package com.cashcontrol.api.service.receipt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * O que se conseguiu ler de um comprovante de pagamento.
 *
 * <p>Todo campo é anulável, e isso é a regra e não a exceção: comprovante não tem formato
 * declarado nem contrato com o banco que o emitiu, então a leitura é sempre melhor esforço.
 * O fluxo assume isso — a tela de revisão pré-preenche o que veio e deixa o resto para o
 * usuário, em vez de recusar o arquivo por não ter entendido um campo.
 *
 * @param amount            valor pago, sempre positivo. O tipo do lançamento não sai do
 *                          comprovante: ele descreve um pagamento feito, mas o mesmo PDF
 *                          serve de comprovante para quem recebeu
 * @param date              data do pagamento
 * @param recipientName     nome de quem recebeu. É o "estabelecimento" deste fluxo: é dele
 *                          que saem {@code merchantKey}, apelido e sugestão de categoria
 * @param recipientDocument CPF/CNPJ de quem recebeu, tipicamente mascarado pelo banco
 * @param endToEndId        identificador fim-a-fim do PIX. Quando existe, é a melhor chave
 *                          de deduplicação possível: o Banco Central garante que ele é
 *                          único para a transferência
 * @param institution       instituição de destino
 * @param message           mensagem/descrição que o pagador escreveu na transferência
 */
public record ParsedReceipt(
        BigDecimal amount,
        LocalDate date,
        String recipientName,
        String recipientDocument,
        String endToEndId,
        String institution,
        String message) {

    public static final ParsedReceipt EMPTY =
            new ParsedReceipt(null, null, null, null, null, null, null);

    /**
     * Os campos que a leitura não encontrou, nomeados como a tela os chama.
     *
     * <p>Existe para a revisão poder dizer <em>o que</em> não foi lido, em vez de mostrar
     * campos vazios sem explicação — a diferença entre "o comprovante não tinha isso" e
     * "o app esqueceu de preencher".
     */
    public List<String> unreadFields() {
        List<String> unread = new ArrayList<>();
        if (amount == null) {
            unread.add("valor");
        }
        if (date == null) {
            unread.add("data");
        }
        if (recipientName == null) {
            unread.add("destinatário");
        }
        return unread;
    }

    /** Se sobrou alguma coisa aproveitável. Nada lido é um resultado válido, só não é útil. */
    public boolean isEmpty() {
        return amount == null && date == null && recipientName == null;
    }
}
