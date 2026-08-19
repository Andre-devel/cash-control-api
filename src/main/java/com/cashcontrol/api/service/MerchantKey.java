package com.cashcontrol.api.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reduz a descrição de um lançamento à identidade do estabelecimento.
 *
 * <p>É a peça que decide se a memória de categorização funciona.
 * {@code SHOPEE *LarkSpComercio (Parcela 04 de 05)} e {@code (Parcela 05 de 05)}
 * são strings diferentes para o mesmo comerciante — sem reduzir as duas à mesma
 * chave, nada aprendido num mês se aplica no seguinte.
 *
 * <p>Estático de propósito: a chave é derivada em um callback JPA da entidade
 * {@code Transaction}, onde não há injeção de dependência. Por isso a classe não
 * é um {@code @Component} e não depende de nada do Spring.
 *
 * <p><strong>Toda etapa precisa ser reproduzível em SQL</strong>, porque o
 * backfill do histórico roda em SQL puro na migração. Se as duas normalizações
 * divergirem, as linhas antigas ficam com chave errada e a memória fica
 * silenciosamente furada. Daí o formato de saída ser deliberadamente pobre —
 * só {@code [a-z0-9]} separado por espaço simples — e cada passo ser um
 * {@code regexp_replace} direto:
 *
 * <pre>
 * 1. sufixo de parcela   regexp_replace(d, '\(\s*parcela\s+\d+\s+de\s+\d+\s*\)', '', 'gi')
 * 2. acentos             unaccent(...)                  -- exige CREATE EXTENSION unaccent
 * 3. caixa               lower(...)
 * 4. prefixo de gateway  regexp_replace(..., '^[a-z0-9]{1,9}[\s ]*\*[\s ]*', '')
 * 5. dígitos longos      regexp_replace(..., '[0-9]{3,}', ' ', 'g')
 * 6. resto não alfanum.  btrim(regexp_replace(..., '[^a-z0-9]+', ' ', 'g'))
 * 7. tamanho             rtrim(left(..., 64))
 * 8. sufixo de praça     nullif(regexp_replace(..., '(\s+(ac|al|...|to|br|bra))+$', ''), '')
 * </pre>
 *
 * <p>O passo 6 é o que dá segurança à equivalência: depois dele não sobra
 * pontuação, espaço duplo nem caractere exótico vindo do PDF para as duas
 * implementações discordarem sobre. O preço é perder hífen e ponto, que
 * praticamente nunca distinguem dois estabelecimentos.
 *
 * <p>A normalização erra para o lado conservador. Chave longa demais só perde
 * uma oportunidade de sugestão; chave curta demais sugere errado com confiança.
 * Por isso corta prefixo (o gateway que processou a compra não é o comerciante)
 * e não corta sufixo significativo — a cidade fica, porque separar "cidade" de
 * "nome do comerciante" sem cadastro é chute, e a cidade é estável de um mês
 * para o outro de qualquer forma. Só o marcador de praça (UF e país), que o
 * emissor acrescenta e omite sem critério, sai.
 */
public final class MerchantKey {

    /** O que cabe na coluna {@code transactions.merchant_key}. */
    private static final int MAX_LENGTH = 64;

    /** O mesmo sufixo que o parser da fatura lê para extrair a posição da parcela. */
    private static final Pattern INSTALLMENT_SUFFIX =
            Pattern.compile("\\(\\s*Parcela\\s+\\d+\\s+de\\s+\\d+\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /**
     * Gateway de pagamento antes do {@code *}: {@code PAG*}, {@code MP*}, {@code PP*},
     * {@code SHOPEE *}. Quem processou a compra não é quem vendeu, e o mesmo comerciante
     * aparece ora com prefixo ora sem. O limite de nove caracteres é o que impede a regra
     * de comer uma descrição inteira que por acaso tenha um asterisco.
     */
    private static final Pattern GATEWAY_PREFIX =
            Pattern.compile("^[a-z0-9]{1,9}[\\s\\u00A0]*\\*[\\s\\u00A0]*");

    /**
     * Identificadores que o emissor cola na descrição — número de pedido, id de cashback.
     * Três dígitos como piso deixa passar o que é nome ({@code POSTO 24 HORAS}).
     */
    private static final Pattern LONG_DIGITS = Pattern.compile("[0-9]{3,}");

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    /** UF, {@code BR} e {@code BRA} no fim, possivelmente em sequência ({@code ... SP BR}). */
    private static final Pattern PLACE_SUFFIX = Pattern.compile(
            "(?:\\s+(?:ac|al|ap|am|ba|ce|df|es|go|ma|mt|ms|mg|pa|pb|pr|pe|pi|rj|rn|rs|ro|rr"
            + "|sc|sp|se|to|br|bra))+$");

    /**
     * Piso de tamanho para um token contar como identidade de estabelecimento. Abaixo
     * disso o token é comum demais em português/inglês (conectivos, siglas curtas) para
     * identificar alguém — casaria coisas sem relação.
     */
    private static final int MIN_TOKEN_LENGTH = 4;

    /**
     * Sufixos de razão social que aparecem em incontáveis descrições sem relação entre si.
     * Não são conectivos curtos (o piso de tamanho já filtra esses), são palavras "de verdade"
     * que passariam no piso e ainda assim não identificam ninguém.
     */
    private static final Set<String> STOPWORD_TOKENS = Set.of("ltda", "eireli");

    private MerchantKey() {}

    /**
     * A chave do estabelecimento de uma descrição.
     *
     * @return {@code null} quando não sobra nada identificável — descrição vazia, ou só
     *         pontuação e números. Devolver {@code null} em vez de string vazia é o que
     *         mantém essas linhas fora do índice parcial e, mais importante, impede que
     *         lançamentos sem nada em comum sejam agrupados por uma chave vazia
     */
    public static String of(String description) {
        if (description == null) {
            return null;
        }
        String key = stripInstallmentSuffix(description);
        key = DIACRITICS.matcher(Normalizer.normalize(key, Normalizer.Form.NFD)).replaceAll("");
        key = key.toLowerCase(Locale.ROOT);
        key = GATEWAY_PREFIX.matcher(key).replaceFirst("");
        key = LONG_DIGITS.matcher(key).replaceAll(" ");
        key = NON_ALPHANUMERIC.matcher(key).replaceAll(" ").trim();
        if (key.length() > MAX_LENGTH) {
            // Cortar antes de tirar o sufixo de praça, e não depois: assim a chave já
            // truncada passa pelas mesmas regras que uma curta, e of(of(x)) == of(x).
            key = key.substring(0, MAX_LENGTH).stripTrailing();
        }
        key = PLACE_SUFFIX.matcher(key).replaceFirst("");
        return key.isEmpty() ? null : key;
    }

    /**
     * A descrição sem o "(Parcela X de Y)".
     *
     * <p>Mora aqui, e não em {@code FaturaRowHasher}, porque os dois precisam do mesmo
     * regex: o hasher tira o sufixo para que a parcela gerada e a linha do mês seguinte
     * produzam a mesma identidade, e a chave de estabelecimento tira pelo mesmo motivo,
     * um nível acima. Duas cópias do padrão seriam duas chances de divergir.
     */
    public static String stripInstallmentSuffix(String description) {
        return INSTALLMENT_SUFFIX.matcher(description).replaceAll("").trim();
    }

    /**
     * Os tokens de uma chave que valem como identidade de estabelecimento, do mais
     * específico (mais longo) para o menos.
     *
     * <p>Existe porque {@link #of} só normaliza formatação: o emissor manda a mesma
     * assinatura com grafias diferentes de um mês para o outro ({@code ANTHROPIC},
     * {@code CLAUDE.AI SUBSCRIPTION}, {@code ANTHROPIC* CLAUDE SUB}) e nenhuma delas
     * reduz à chave da outra. Quem consulta a memória por estabelecimento — categoria
     * ({@code CategorySuggester}) e apelido ({@code MerchantAliasService}) — cai neste
     * casamento por palavra quando a chave exata não bate, e precisa dos dois usando a
     * mesma heurística: duas cópias seriam duas chances de divergir sobre o que conta
     * como token significativo.
     *
     * <p>A ordem (mais longo primeiro) é o critério de desempate quando mais de um token
     * da linha tem entrada na memória: o token mais longo é o mais específico.
     */
    public static List<String> significantTokens(String merchantKey) {
        if (merchantKey == null) {
            return List.of();
        }
        return Arrays.stream(merchantKey.split(" "))
                .filter(token -> token.length() >= MIN_TOKEN_LENGTH && !STOPWORD_TOKENS.contains(token))
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }
}
