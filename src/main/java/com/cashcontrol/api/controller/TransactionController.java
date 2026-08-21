package com.cashcontrol.api.controller;

import com.cashcontrol.api.dto.request.CreateTransactionRequest;
import com.cashcontrol.api.dto.request.EditTransactionRequest;
import com.cashcontrol.api.dto.request.ImportCommitRequest;
import com.cashcontrol.api.dto.request.MarkAsPaidRequest;
import com.cashcontrol.api.dto.request.ReceiptCommitRequest;
import com.cashcontrol.api.dto.request.TransactionFilterRequest;
import com.cashcontrol.api.dto.response.AttachmentResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.ImportPreviewResponse;
import com.cashcontrol.api.dto.response.ImportResultResponse;
import com.cashcontrol.api.dto.response.ReceiptPreviewResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.dto.response.TransactionSummaryResponse;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.StatementFormat;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.entity.TransactionType;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.AttachmentService;
import com.cashcontrol.api.service.ReceiptImportService;
import com.cashcontrol.api.service.StatementImportService;
import com.cashcontrol.api.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Transactions", description = "Financial transaction lifecycle management")
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final TransactionService transactionService;
    private final AttachmentService attachmentService;
    private final StatementImportService statementImportService;
    private final ReceiptImportService receiptImportService;

    @Operation(summary = "Create transaction", description = "Records a new INCOME, EXPENSE, or REFUND transaction for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transaction created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public TransactionDetailResponse createTransaction(
            @Valid @RequestBody CreateTransactionRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transactionService.createTransaction(request, principal.getUser().getId());
    }

    @Operation(summary = "List transactions", description = """
            Returns a filtered, paginated list of transactions for the authenticated user.

            With `groupInstallments=true` an installment series collapses into a single row standing for the whole \
            purchase: `amount` and `installmentTotalAmount` carry the full price, `totalInstallments` the number of \
            instalments, and `status` is derived from the set (PAID only when every instalment is settled).""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Page<TransactionSummaryResponse> listTransactions(
            @Parameter(description = "Filter by account UUID") @RequestParam(required = false) UUID accountId,
            @Parameter(description = "Filter by transaction type") @RequestParam(required = false) TransactionType type,
            @Parameter(description = "Filter by status") @RequestParam(required = false) TransactionStatus status,
            @Parameter(description = "Filter by category UUID") @RequestParam(required = false) UUID categoryId,
            @Parameter(description = "Competence date from (inclusive)") @RequestParam(required = false) LocalDate competenceDateFrom,
            @Parameter(description = "Competence date to (inclusive)") @RequestParam(required = false) LocalDate competenceDateTo,
            @Parameter(description = "Payment date from (inclusive)") @RequestParam(required = false) LocalDate paymentDateFrom,
            @Parameter(description = "Payment date to (inclusive)") @RequestParam(required = false) LocalDate paymentDateTo,
            @Parameter(description = "Minimum amount") @RequestParam(required = false) BigDecimal amountMin,
            @Parameter(description = "Maximum amount") @RequestParam(required = false) BigDecimal amountMax,
            @Parameter(description = "Full-text search on description and notes") @RequestParam(required = false) String searchText,
            @Parameter(description = "Include cancelled transactions") @RequestParam(defaultValue = "false") boolean includeCancelled,
            @Parameter(description = "Filter by payment method slug") @RequestParam(required = false) PaymentMethodSlug paymentMethod,
            @Parameter(description = "Collapse each installment series into a single row representing the whole purchase")
            @RequestParam(defaultValue = "false") boolean groupInstallments,
            @PageableDefault(sort = "competenceDate", direction = Sort.Direction.DESC) Pageable pageable,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        TransactionFilterRequest filter = new TransactionFilterRequest(
                accountId, type, status, categoryId,
                competenceDateFrom, competenceDateTo,
                paymentDateFrom, paymentDateTo,
                amountMin, amountMax, searchText, includeCancelled, paymentMethod, groupInstallments);

        return transactionService.listTransactions(filter, principal.getUser().getId(), pageable);
    }

    @Operation(summary = "Get transaction", description = "Returns full detail of a single transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public TransactionDetailResponse getTransaction(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transactionService.getTransaction(id, principal.getUser().getId());
    }

    @Operation(summary = "Edit transaction", description = "Updates editable fields of an existing transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction updated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public TransactionDetailResponse editTransaction(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody EditTransactionRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transactionService.editTransaction(id, request, principal.getUser().getId());
    }

    @Operation(summary = "Delete transaction", description = "Permanently removes a transaction. Transfer legs must use the dedicated transfer delete endpoint, and installments must use the installment series endpoints.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Transaction deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation (transfer leg, installment)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void deleteTransaction(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        transactionService.deleteTransaction(id, principal.getUser().getId());
    }

    @Operation(summary = "Mark as paid", description = "Transitions a PENDING or OVERDUE transaction to PAID status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction marked as paid"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Invalid status transition",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/pay")
    @PreAuthorize("isAuthenticated()")
    public TransactionDetailResponse markAsPaid(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable UUID id,
            @RequestBody(required = false) MarkAsPaidRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transactionService.markAsPaid(id, request != null ? request : new MarkAsPaidRequest(null),
                principal.getUser().getId());
    }

    @Operation(summary = "Cancel transaction", description = "Cancels a transaction. Cancelled transactions are excluded from balance calculations but preserved for audit.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction cancelled"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Transaction already cancelled",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public TransactionDetailResponse cancelTransaction(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return transactionService.cancelTransaction(id, principal.getUser().getId());
    }

    @Operation(summary = "Preview statement import", description = """
            Reads a bank statement file and returns every entry already classified (type, payment method, \
            suggested category) together with a duplicate flag, **without persisting anything**.

            Entries already imported into this account are detected by `externalRef`, a deterministic hash \
            of the source row, so overlapping export periods do not duplicate. Send the approved rows back \
            to `POST /api/v1/transactions/import` to persist them.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Unreadable file, archived account, or row limit exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ImportPreviewResponse previewImport(
            @Parameter(description = "Statement file", required = true) @RequestPart("file") MultipartFile file,
            @Parameter(description = "Account UUID that will receive the transactions", required = true)
            @RequestParam UUID accountId,
            @Parameter(description = "Statement format") @RequestParam(defaultValue = "INTER_CSV") StatementFormat format,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return statementImportService.preview(file, format, accountId, principal.getUser().getId());
    }

    @Operation(summary = "Commit statement import", description = """
            Persists the statement rows the user approved in the preview.

            Rows whose `externalRef` already exists in the account are skipped, so replaying the same \
            payload is a no-op. Rows that fail validation are reported individually and do not prevent \
            the remaining ones from being imported.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Import finished"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Archived account or row limit exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public ImportResultResponse commitImport(
            @Valid @RequestBody ImportCommitRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return statementImportService.commit(request, principal.getUser().getId());
    }

    @Operation(summary = "Preview receipt import", description = """
            Reads a payment receipt (PIX proof) — PDF or, when OCR is enabled, an image — and returns \
            whatever could be identified (amount, date, recipient, category suggestion), together with \
            a duplicate flag, **without persisting anything**.

            `accountId` is optional: in the share-target flow the account has not been chosen yet when \
            the receipt arrives, so duplicate detection is skipped until `POST /api/v1/transactions/receipts` \
            is called, which always checks against the given account.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Empty file or file exceeds the size limit",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/receipts/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ReceiptPreviewResponse previewReceipt(
            @Parameter(description = "Receipt file (PDF or image)", required = true) @RequestPart("file") MultipartFile file,
            @Parameter(description = "Account UUID that will receive the transaction, when already known")
            @RequestParam(required = false) UUID accountId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return receiptImportService.preview(file, accountId, principal.getUser().getId());
    }

    @Operation(summary = "Commit receipt import", description = """
            Persists the transaction the user reviewed from a receipt preview, attaching the original \
            file to it in the same step.

            Rejects with 409 when a transaction with the same `externalRef` already exists in the \
            account, so retrying the same payload after a network hiccup is safe.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transaction created and receipt attached"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Account, category, or subcategory not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "This receipt was already recorded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Archived account or unsupported file",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public TransactionDetailResponse commitReceipt(
            @Parameter(description = "Reviewed transaction fields", required = true)
            @RequestPart("data") @Valid ReceiptCommitRequest data,
            @Parameter(description = "The same receipt file sent to the preview endpoint", required = true)
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return receiptImportService.commit(data, file, principal.getUser().getId());
    }

    @Operation(summary = "Upload attachments", description = "Attaches one or more receipt or proof-of-payment files to a transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Attachments uploaded"),
            @ApiResponse(responseCode = "400", description = "Unsupported file type",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "File size or limit exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public List<AttachmentResponse> uploadAttachments(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable UUID id,
            @RequestPart("files") MultipartFile[] files,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return attachmentService.attach(id, files, principal.getUser().getId());
    }

    @Operation(summary = "List attachments", description = "Returns metadata for all active attachments on a transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attachment list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/attachments")
    @PreAuthorize("isAuthenticated()")
    public List<AttachmentResponse> listAttachments(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return attachmentService.getAttachments(id, principal.getUser().getId());
    }

    @Operation(summary = "Delete attachment", description = "Soft-deletes a single attachment from a transaction.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Attachment deleted"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Attachment not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void deleteAttachment(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Attachment UUID", required = true) @PathVariable UUID attachmentId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        attachmentService.deleteAttachment(attachmentId, principal.getUser().getId());
    }
}
