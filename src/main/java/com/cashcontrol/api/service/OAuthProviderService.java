package com.cashcontrol.api.service;

import java.util.UUID;

public interface OAuthProviderService {

    void unlinkProvider(UUID userId, String providerSlug);
}
