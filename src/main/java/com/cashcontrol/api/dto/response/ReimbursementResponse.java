package com.cashcontrol.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = """
        Reimbursement link of a transaction. On an EXPENSE, `reimbursable` is true and `linked*` describe the \
        INCOME that paid it back (null while pending). On an INCOME, `reimbursable` is false and `linked*` \
        describe the EXPENSE it paid back. Null on the transaction when neither applies.""")
public record ReimbursementResponse(
        @Schema(description = "True on an expense marked as reimbursable") boolean reimbursable,
        @Schema(description = "UUID of the other side of the link") UUID linkedTransactionId,
        @Schema(description = "Description of the other side of the link") String linkedDescription,
        @Schema(description = "Amount of the other side of the link. Serialized as decimal string.") BigDecimal linkedAmount
) {}
