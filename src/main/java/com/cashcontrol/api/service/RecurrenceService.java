package com.cashcontrol.api.service;

import com.cashcontrol.api.domain.entity.DeleteRecurrenceStrategy;
import com.cashcontrol.api.dto.request.CreateRecurrenceRequest;
import com.cashcontrol.api.dto.request.EditRecurrenceRequest;
import com.cashcontrol.api.dto.request.PauseRecurrenceRequest;
import com.cashcontrol.api.dto.response.DeleteRecurrenceResult;
import com.cashcontrol.api.dto.response.EditRecurrenceResult;
import com.cashcontrol.api.dto.response.RecurrenceCreationResponse;
import com.cashcontrol.api.dto.response.RecurrenceRuleResponse;
import com.cashcontrol.api.dto.response.RecurrenceSuggestionResponse;

import java.util.List;
import java.util.UUID;

public interface RecurrenceService {

    RecurrenceCreationResponse createRecurrence(CreateRecurrenceRequest request, UUID userId);

    EditRecurrenceResult editSeries(UUID ruleId, EditRecurrenceRequest request, UUID userId);

    RecurrenceRuleResponse pauseRecurrence(UUID ruleId, PauseRecurrenceRequest request, UUID userId);

    RecurrenceRuleResponse resumeRecurrence(UUID ruleId, UUID userId);

    DeleteRecurrenceResult deleteRecurrence(UUID ruleId, DeleteRecurrenceStrategy strategy, UUID userId);

    List<RecurrenceRuleResponse> listRecurrences(UUID userId);

    RecurrenceRuleResponse getRecurrence(UUID ruleId, UUID userId);

    /**
     * Despesas que se repetem nos últimos meses sem recorrência ativa correspondente, menos as recusadas.
     * Ver {@link RecurrenceDetector}.
     */
    List<RecurrenceSuggestionResponse> listSuggestions(UUID userId);

    /** Recusa a sugestão do padrão; ela deixa de aparecer em {@link #listSuggestions}. */
    void dismissSuggestion(String pattern, UUID userId);

    int generatePendingInstances(int lookaheadDays);

    /** Retoma as recorrências pausadas cuja data de retomada chegou. */
    int resumeDueRecurrences();
}
