package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.MerchantAlias;
import com.cashcontrol.api.repository.MerchantAliasRepository;
import com.cashcontrol.api.service.MerchantAliasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A memória de apelido isolada do import: o que ela grava, o que ela apaga e o que ela
 * responde na importação seguinte.
 */
@ExtendWith(MockitoExtension.class)
class MerchantAliasServiceTest {

    @Mock private MerchantAliasRepository merchantAliasRepository;

    private MerchantAliasService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        service = new MerchantAliasService(merchantAliasRepository);
    }

    // ── remember ──────────────────────────────────────────────────────────────

    @Test
    void remember_savesTheRenamedDescriptionUnderTheKeyOfTheOriginal() {
        when(merchantAliasRepository.findByUserIdAndMerchantKey(userId, "claude sub"))
                .thenReturn(Optional.empty());

        service.remember(userId, "ANTHROPIC* CLAUDE SUB", "Claude - mensalidade");

        ArgumentCaptor<MerchantAlias> saved = ArgumentCaptor.forClass(MerchantAlias.class);
        verify(merchantAliasRepository).save(saved.capture());
        // A chave é a do texto do arquivo, não a do apelido: é o texto do arquivo que volta
        // igual no mês que vem. "ANTHROPIC*" sai porque MerchantKey trata o que vem antes
        // do "*" como gateway de pagamento.
        assertThat(saved.getValue().getMerchantKey()).isEqualTo("claude sub");
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Claude - mensalidade");
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void remember_overwritesThePreviousAliasOfTheSameMerchant() {
        MerchantAlias existing = alias("claude sub", "Claude", Instant.now().minus(60, ChronoUnit.DAYS));
        when(merchantAliasRepository.findByUserIdAndMerchantKey(userId, "claude sub"))
                .thenReturn(Optional.of(existing));

        service.remember(userId, "ANTHROPIC* CLAUDE SUB", "Claude - mensalidade");

        assertThat(existing.getDisplayName()).isEqualTo("Claude - mensalidade");
        verify(merchantAliasRepository).save(existing);
    }

    @Test
    void remember_refreshesTheTimestampEvenWhenTheAliasIsConfirmedUnchanged() {
        Instant old = Instant.now().minus(60, ChronoUnit.DAYS);
        MerchantAlias existing = alias("claude sub", "Claude - mensalidade", old);
        when(merchantAliasRepository.findByUserIdAndMerchantKey(userId, "claude sub"))
                .thenReturn(Optional.of(existing));

        service.remember(userId, "ANTHROPIC* CLAUDE SUB", "Claude - mensalidade");

        // Sem isso o Hibernate não veria mudança nenhuma, não emitiria UPDATE, e a memória
        // ficaria parada no dia da criação — envelhecendo no desempate por token.
        assertThat(existing.getUpdatedAt()).isAfter(old);
    }

    @Test
    void remember_deletesTheAliasWhenTheUserGoesBackToTheOriginalDescription() {
        service.remember(userId, "ANTHROPIC* CLAUDE SUB", "ANTHROPIC* CLAUDE SUB");

        verify(merchantAliasRepository).deleteByUserIdAndMerchantKey(userId, "claude sub");
        verify(merchantAliasRepository, never()).save(any());
    }

    @Test
    void remember_doesNotTreatTheInstallmentSuffixAsARename() {
        // O sufixo muda todo mês sozinho; gravar isso como apelido colaria "(Parcela 04 de
        // 05)" na compra do mês seguinte, que está na parcela 5.
        service.remember(userId, "SHOPEE *LarkSpComercio (Parcela 04 de 05)", "SHOPEE *LarkSpComercio");

        verify(merchantAliasRepository).deleteByUserIdAndMerchantKey(userId, "larkspcomercio");
        verify(merchantAliasRepository, never()).save(any());
    }

    @Test
    void remember_ignoresDescriptionsWithNoIdentifiableMerchant() {
        service.remember(userId, "  ---  ", "Alguma coisa");

        verify(merchantAliasRepository, never()).save(any());
        verify(merchantAliasRepository, never()).deleteByUserIdAndMerchantKey(any(), any());
    }

    // ── suggest ───────────────────────────────────────────────────────────────

    @Test
    void suggest_returnsTheAliasOfTheSameMerchantKey() {
        givenAliases(alias("claude sub", "Claude - mensalidade", Instant.now()));

        assertThat(service.suggest("ANTHROPIC* CLAUDE SUB", service.load(userId)))
                .isEqualTo("Claude - mensalidade");
    }

    @Test
    void suggest_fallsBackToASharedTokenWhenTheIssuerChangesTheSpelling() {
        givenAliases(alias("claude sub", "Claude - mensalidade", Instant.now()));

        // Mesmo comerciante, outra grafia: nenhuma das duas chaves reduz à outra, e é o
        // token "claude" que as liga.
        assertThat(service.suggest("CLAUDE.AI SUBSCRIPTION", service.load(userId)))
                .isEqualTo("Claude - mensalidade");
    }

    @Test
    void suggest_prefersTheMostRecentAliasWhenTwoMerchantsShareAToken() {
        givenAliases(
                alias("claude ai subscription", "Claude antigo", Instant.now().minus(90, ChronoUnit.DAYS)),
                alias("claude sub", "Claude - mensalidade", Instant.now()));

        assertThat(service.suggest("CLAUDE PRO", service.load(userId))).isEqualTo("Claude - mensalidade");
    }

    @Test
    void suggest_returnsNullForAMerchantTheUserNeverRenamed() {
        givenAliases(alias("claude sub", "Claude - mensalidade", Instant.now()));

        assertThat(service.suggest("PADARIA SAO JOAO", service.load(userId))).isNull();
    }

    @Test
    void suggest_returnsNullWhenTheDescriptionHasNoIdentifiableMerchant() {
        givenAliases(alias("claude sub", "Claude - mensalidade", Instant.now()));

        assertThat(service.suggest("  ---  ", service.load(userId))).isNull();
    }

    @Test
    void load_readsNothingBeyondTheUsersOwnAliases() {
        when(merchantAliasRepository.findAllByUserId(userId)).thenReturn(List.of());

        assertThat(service.load(userId)).isEqualTo(MerchantAliasService.Aliases.EMPTY);
        verify(merchantAliasRepository).findAllByUserId(eq(userId));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void givenAliases(MerchantAlias... aliases) {
        when(merchantAliasRepository.findAllByUserId(userId)).thenReturn(List.of(aliases));
    }

    private MerchantAlias alias(String merchantKey, String displayName, Instant updatedAt) {
        MerchantAlias alias = new MerchantAlias();
        ReflectionTestUtils.setField(alias, "id", UUID.randomUUID());
        alias.setUserId(userId);
        alias.setMerchantKey(merchantKey);
        alias.setDisplayName(displayName);
        alias.setUpdatedAt(updatedAt);
        return alias;
    }
}
