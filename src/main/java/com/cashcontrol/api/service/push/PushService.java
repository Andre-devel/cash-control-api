package com.cashcontrol.api.service.push;

/**
 * @author Andre
 * 30 de Outubro. de 2026
 */

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.PushSubscription;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.dto.request.PushSubscriptionRequest;
import com.cashcontrol.api.dto.response.PushConfigResponse;
import com.cashcontrol.api.dto.response.PushSubscriptionResponse;
import com.cashcontrol.api.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Aparelhos que recebem notificação, um por inscrição do navegador. */
@Service
@RequiredArgsConstructor
public class PushService {

    /**
     * Serviços de push dos navegadores. O servidor faz POST no endpoint que o cliente mandou;
     * sem esta lista, qualquer usuário apontaria o servidor para um endereço interno.
     */
    private static final Set<String> PUSH_HOSTS = Set.of(
            "fcm.googleapis.com", "updates.push.services.mozilla.com", "push.apple.com", "notify.windows.com");

    private final PushSubscriptionRepository subscriptionRepository;
    private final WebPushSender sender;
    private final AppProperties appProperties;

    public PushConfigResponse config() {
        boolean enabled = appProperties.getPush().isEnabled();
        return new PushConfigResponse(enabled, enabled ? appProperties.getPush().getVapidPublicKey() : null);
    }

    @Transactional(readOnly = true)
    public List<PushSubscriptionResponse> list(UUID userId) {
        return subscriptionRepository.findAllByUserId(userId).stream().map(PushService::toResponse).toList();
    }

    /**
     * Liga (ou atualiza) as notificações do aparelho. O endpoint identifica o aparelho: se outro
     * usuário entrou nele antes, a inscrição passa para quem está logado agora.
     */
    @Transactional
    public PushSubscriptionResponse subscribe(UUID userId, PushSubscriptionRequest request) {
        if (!appProperties.getPush().isEnabled()) {
            throw new BusinessRuleException("As notificações não estão configuradas neste servidor.");
        }
        requirePushService(request.endpoint());
        PushSubscription subscription = subscriptionRepository.findByEndpoint(request.endpoint())
                .orElseGet(() -> {
                    PushSubscription created = new PushSubscription();
                    created.setEndpoint(request.endpoint());
                    return created;
                });
        subscription.setUserId(userId);
        subscription.setP256dh(request.p256dh());
        subscription.setAuth(request.auth());
        subscription.setDeviceLabel(request.deviceLabel());
        subscription.setHideAmounts(request.hideAmounts());
        return toResponse(subscriptionRepository.save(subscription));
    }

    @Transactional
    public void unsubscribe(UUID userId, String endpoint) {
        subscriptionRepository.findByEndpointAndUserId(endpoint, userId).ifPresent(subscriptionRepository::delete);
    }

    /**
     * Manda a cada aparelho do usuário a mensagem que {@code payloadFor} monta para ele (a de quem
     * esconde valores é outra). Aparelho que o serviço de push não conhece mais é apagado.
     *
     * @return quantos aparelhos receberam
     */
    @Transactional
    public int send(UUID userId, Function<PushSubscription, String> payloadFor) {
        if (!appProperties.getPush().isEnabled()) {
            return 0;
        }
        int delivered = 0;
        for (PushSubscription subscription : subscriptionRepository.findAllByUserId(userId)) {
            switch (sender.send(subscription, payloadFor.apply(subscription))) {
                case DELIVERED -> {
                    subscription.setLastSuccessAt(Instant.now());
                    delivered++;
                }
                case GONE -> subscriptionRepository.delete(subscription);
                case FAILED -> { }
            }
        }
        return delivered;
    }

    private static void requirePushService(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("Endereço de notificação inválido.");
        }
        String host = uri.getHost();
        boolean known = "https".equals(uri.getScheme()) && host != null && uri.getPort() == -1
                        && PUSH_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!known) {
            throw new BusinessRuleException("Serviço de notificação não suportado.");
        }
    }

    private static PushSubscriptionResponse toResponse(PushSubscription subscription) {
        return new PushSubscriptionResponse(subscription.getEndpoint(), subscription.getDeviceLabel(),
                subscription.isHideAmounts(), subscription.getCreatedAt());
    }
}
