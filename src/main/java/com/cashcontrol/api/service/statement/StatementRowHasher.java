package com.cashcontrol.api.service.statement;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Calcula a chave de deduplicação de cada linha do extrato.
 *
 * <p>O problema: extratos são exportados por período, e quem importa de novo
 * quase sempre pede um recorte que se sobrepõe ao anterior. Sem chave estável,
 * a sobreposição duplica.
 *
 * <p>A chave é o SHA-256 de {@code data|histórico|descrição|valor|#ordinal}.
 * O {@code ordinal} distingue linhas <em>idênticas</em> — duas compras de R$ 8,00
 * no mesmo café no mesmo dia são dois fatos, não uma duplicata — e é contado
 * dentro do grupo de linhas iguais <em>do mesmo dia</em>, nunca globalmente:
 * é isso que mantém o hash estável quando o próximo export começa em outra data.
 */
@Component
public class StatementRowHasher {

    /**
     * @return os hashes na mesma ordem das linhas recebidas
     */
    public List<String> hashAll(List<ParsedStatementRow> rows) {
        Map<String, Integer> seen = new HashMap<>();
        return rows.stream()
                .map(row -> {
                    String identity = identityOf(row);
                    int ordinal = seen.merge(identity, 1, Integer::sum) - 1;
                    return sha256(identity + "|#" + ordinal);
                })
                .toList();
    }

    private String identityOf(ParsedStatementRow row) {
        return row.date()
               + "|" + StatementHistoryMapper.normalize(row.rawHistory())
               + "|" + StatementHistoryMapper.normalize(row.description())
               + "|" + row.signedAmount().stripTrailingZeros().toPlainString();
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
