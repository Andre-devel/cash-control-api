package com.cashcontrol.api.service;

import java.util.UUID;

public interface AnonymizationService {

    void anonymizeUser(UUID userId);
}
