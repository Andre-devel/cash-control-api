package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.MerchantAlias;
import com.cashcontrol.api.repository.MerchantAliasRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A memória de como o usuário renomeia cada estabelecimento na importação.
 *
 * <p>Mesma forma do {@link CategorySuggester}: {@link #load} uma vez por arquivo,
 * {@link #suggest} por linha. A diferença é a origem do dado — a categoria é inferida do
 * histórico de transações, o apelido é gravado explicitamente por {@link #remember} quando o
 * usuário confirma a importação, porque não existe em lugar nenhum a informação "esta
 * descrição substitui aquela".
 *
 * <p>A chave é sempre derivada da descrição <strong>original do arquivo</strong>. Usar a
 * renomeada seria circular: o apelido é justamente o que não se repete no arquivo do mês
 * seguinte.
 */
@Service
public class MerchantAliasService {

    private final MerchantAliasRepository merchantAliasRepository;

    public MerchantAliasService(MerchantAliasRepository merchantAliasRepository) {
        this.merchantAliasRepository = merchantAliasRepository;
    }

    /**
     * Os apelidos do usuário indexados das duas formas que {@link #suggest} consulta: por
     * {@code merchantKey} exato e por token significativo.
     */
    public record Aliases(Map<String, String> byMerchantKey, Map<String, String> byToken) {

        public static final Aliases EMPTY = new Aliases(Map.of(), Map.of());
    }

    @Transactional(readOnly = true)
    public Aliases load(UUID userId) {
        List<MerchantAlias> aliases = merchantAliasRepository.findAllByUserId(userId);
        if (aliases.isEmpty()) {
            return Aliases.EMPTY;
        }

        Map<String, String> byMerchantKey = new HashMap<>();
        Map<String, String> byToken = new HashMap<>();
        Map<String, Instant> byTokenAge = new HashMap<>();

        for (MerchantAlias alias : aliases) {
            byMerchantKey.put(alias.getMerchantKey(), alias.getDisplayName());
            for (String token : MerchantKey.significantTokens(alias.getMerchantKey())) {
                // Dois estabelecimentos podem compartilhar um token ("CLAUDE.AI" e
                // "ANTHROPIC* CLAUDE SUB"); vence o apelido confirmado mais recentemente,
                // que é a decisão mais atual do usuário sobre aquela palavra.
                Instant current = byTokenAge.get(token);
                if (current != null && current.isAfter(alias.getUpdatedAt())) {
                    continue;
                }
                byTokenAge.put(token, alias.getUpdatedAt());
                byToken.put(token, alias.getDisplayName());
            }
        }
        return new Aliases(byMerchantKey, byToken);
    }

    /**
     * O apelido para uma linha da prévia, ou {@code null} quando o usuário nunca renomeou
     * aquele estabelecimento.
     *
     * <p>Chave exata primeiro; depois o token mais específico, pelo mesmo motivo que a
     * sugestão de categoria tem esse fallback — ver {@link MerchantKey#significantTokens}.
     *
     * @param originalDescription a descrição como o arquivo a trouxe, nunca uma já editada
     */
    public String suggest(String originalDescription, Aliases aliases) {
        String merchantKey = MerchantKey.of(originalDescription);
        if (merchantKey == null) {
            return null;
        }

        String exact = aliases.byMerchantKey().get(merchantKey);
        if (exact != null) {
            return exact;
        }

        for (String token : MerchantKey.significantTokens(merchantKey)) {
            String fromToken = aliases.byToken().get(token);
            if (fromToken != null) {
                return fromToken;
            }
        }
        return null;
    }

    /**
     * Grava o que o usuário decidiu para aquele estabelecimento nesta importação.
     *
     * <p>Chamado para toda linha confirmada, não só para as editadas: reconfirmar um apelido
     * que já veio pré-preenchido renova o {@code updatedAt}, e é isso que mantém a decisão
     * mais recente ganhando o desempate por token.
     *
     * <p>Quando a descrição final volta a ser a original, o apelido é <strong>apagado</strong>.
     * Desfazer é uma decisão tão explícita quanto renomear: sem isso, o usuário que clicasse
     * em "usar original" veria o apelido de volta na importação seguinte. A comparação ignora
     * o sufixo de parcela, que muda de um mês para o outro sem ser uma renomeação.
     */
    @Transactional
    public void remember(UUID userId, String originalDescription, String finalDescription) {
        String merchantKey = MerchantKey.of(originalDescription);
        if (merchantKey == null || finalDescription == null || finalDescription.isBlank()) {
            return;
        }

        String displayName = finalDescription.trim();
        if (isSameDescription(originalDescription, displayName)) {
            merchantAliasRepository.deleteByUserIdAndMerchantKey(userId, merchantKey);
            return;
        }

        MerchantAlias alias = merchantAliasRepository.findByUserIdAndMerchantKey(userId, merchantKey)
                .orElseGet(() -> {
                    MerchantAlias created = new MerchantAlias();
                    created.setUserId(userId);
                    created.setMerchantKey(merchantKey);
                    return created;
                });
        alias.setDisplayName(displayName);
        // Sem isto, reconfirmar o mesmo apelido não sujaria a entidade, o Hibernate não
        // emitiria UPDATE nenhum e o @UpdateTimestamp não avançaria — a memória pareceria
        // parada no dia em que o apelido foi criado.
        alias.setUpdatedAt(Instant.now());
        merchantAliasRepository.save(alias);
    }

    private static boolean isSameDescription(String originalDescription, String displayName) {
        return Objects.equals(
                MerchantKey.stripInstallmentSuffix(originalDescription),
                MerchantKey.stripInstallmentSuffix(displayName));
    }
}
