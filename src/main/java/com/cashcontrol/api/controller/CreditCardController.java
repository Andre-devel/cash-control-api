package com.cashcontrol.api.controller;

import com.cashcontrol.api.domain.entity.InvoiceImportFormat;
import com.cashcontrol.api.dto.request.CreateCardRequest;
import com.cashcontrol.api.dto.request.EditCardRequest;
import com.cashcontrol.api.dto.request.FaturaImportCommitRequest;
import com.cashcontrol.api.dto.request.FaturaImportDuplicateCheckRequest;
import com.cashcontrol.api.dto.request.PayInvoiceRequest;
import com.cashcontrol.api.dto.request.RecordChargeRequest;
import com.cashcontrol.api.dto.request.UpdateInvoiceItemRequest;
import com.cashcontrol.api.dto.response.CreditCardResponse;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.dto.response.FaturaImportDuplicateCheckResponse;
import com.cashcontrol.api.dto.response.FaturaImportPreviewResponse;
import com.cashcontrol.api.dto.response.FaturaImportResultResponse;
import com.cashcontrol.api.dto.response.InvoiceItemResponse;
import com.cashcontrol.api.dto.response.InvoiceResponse;
import com.cashcontrol.api.dto.response.InvoiceSummaryResponse;
import com.cashcontrol.api.dto.response.LimitUsageResponse;
import com.cashcontrol.api.dto.response.MerchantScopeResponse;
import com.cashcontrol.api.dto.response.SpendingByCategoryResponse;
import com.cashcontrol.api.dto.response.UpdateInvoiceItemResponse;
import com.cashcontrol.api.security.AuthenticatedUser;
import com.cashcontrol.api.service.CreditCardService;
import com.cashcontrol.api.service.FaturaImportService;
import com.cashcontrol.api.service.InvoiceManagementService;
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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Credit Cards", description = "Credit card management, charge recording, invoice lifecycle, and spending analysis")
@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class CreditCardController {

    private final CreditCardService creditCardService;
    private final FaturaImportService faturaImportService;
    private final InvoiceManagementService invoiceManagementService;

    @Operation(summary = "Create credit card", description = "Registers a new credit card for the authenticated user and opens the first invoice.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Credit card created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Card name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public CreditCardResponse createCard(
            @Valid @RequestBody CreateCardRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.createCard(request, principal.getUser().getId());
    }

    @Operation(summary = "List credit cards", description = "Returns all credit cards for the authenticated user (including archived).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Card list returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<CreditCardResponse> listCards(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.listCards(principal.getUser().getId());
    }

    @Operation(summary = "Edit credit card", description = "Updates credit card details such as name, brand, issuer, limit, and billing cycle days.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Card updated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Card not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Card name already exists",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public CreditCardResponse editCard(
            @Parameter(description = "Credit card UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody EditCardRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.editCard(id, request, principal.getUser().getId());
    }

    @Operation(summary = "Archive credit card", description = "Marks a credit card as archived. Archived cards cannot receive new charges.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Card archived"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Card not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Card is already archived",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/archive")
    @PreAuthorize("isAuthenticated()")
    public CreditCardResponse archiveCard(
            @Parameter(description = "Credit card UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.archiveCard(id, principal.getUser().getId());
    }

    @Operation(summary = "Record charge", description = "Records a charge on the credit card. The charge is assigned to the correct invoice based on the competence date and closing day.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Charge recorded"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Card not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Card is archived",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/charges")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public InvoiceItemResponse recordCharge(
            @Parameter(description = "Credit card UUID", required = true) @PathVariable UUID id,
            @Valid @RequestBody RecordChargeRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.recordCharge(id, request, principal.getUser().getId());
    }

    @Operation(summary = "Get invoice", description = "Returns invoice details with paginated charge items for the given reference month (YYYY-MM).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/invoices/{referenceMonth}")
    @PreAuthorize("isAuthenticated()")
    public InvoiceResponse getInvoice(
            @Parameter(description = "Credit card UUID", required = true) @PathVariable UUID id,
            @Parameter(description = "Reference month in YYYY-MM format", required = true) @PathVariable String referenceMonth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.getInvoice(id, referenceMonth, principal.getUser().getId(), page, size);
    }

    @Operation(summary = "Preview invoice import", description = """
            Reads a credit card invoice PDF and returns its charges grouped by card section, \
            **without persisting anything**.

            A single Inter invoice covers the primary card and its additional cards, so the response \
            is a list of groups: each one carries the last four digits read from the PDF and, when a \
            registered card has the same `last4Digits`, the suggested card to import into. Credits \
            (invoice payments, refunds) are dropped and only counted. Charges already imported into \
            that month's invoice are flagged by `externalRef`, so re-importing the same PDF is safe. \
            Send the approved rows back to `POST /api/v1/cards/invoices/import` to persist them.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Unreadable file or unsupported format",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(value = "/invoices/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    public FaturaImportPreviewResponse previewInvoiceImport(
            @Parameter(description = "Invoice PDF file", required = true) @RequestPart("file") MultipartFile file,
            @Parameter(description = "Invoice file format")
            @RequestParam(defaultValue = "INTER_FATURA_PDF") InvoiceImportFormat format,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return faturaImportService.preview(file, format, principal.getUser().getId());
    }

    @Operation(summary = "Check invoice import duplicates", description = """
            Tells which preview rows are already on the invoice of a card the user picked by hand.

            The preview can only flag duplicates for the sections whose four digits matched a \
            registered card. When the destination card is chosen in the client — a virtual card, \
            say — the flags belong to a different invoice, and this endpoint recomputes them for \
            the given `externalRef` list. Nothing is persisted.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Duplicates returned"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Credit card not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Archived card or invalid reference month",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/invoices/import/duplicates")
    @PreAuthorize("isAuthenticated()")
    public FaturaImportDuplicateCheckResponse checkInvoiceImportDuplicates(
            @Valid @RequestBody FaturaImportDuplicateCheckRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return faturaImportService.checkDuplicates(request, principal.getUser().getId());
    }

    @Operation(summary = "Commit invoice import", description = """
            Persists the invoice charges the user approved in the preview.

            Each row carries the credit card chosen for its group, and every row lands on that card's \
            invoice for `referenceMonth` — the invoice is opened if it does not exist yet. Rows whose \
            `externalRef` already exists on that invoice are skipped, so replaying the same payload is \
            a no-op. A card whose invoice already received a payment is reported per row and does not \
            prevent the other cards in the same PDF from being imported.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Import finished"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Invalid reference month",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/invoices/import")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public FaturaImportResultResponse commitInvoiceImport(
            @Valid @RequestBody FaturaImportCommitRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return faturaImportService.commit(request, principal.getUser().getId());
    }

    @Operation(summary = "Pay invoice", description = "Pays an invoice fully or partially. A partial payment creates a revolving balance item on the next invoice.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice payment processed"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice or account not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Business rule violation",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/invoices/{invoiceId}/pay")
    @PreAuthorize("isAuthenticated()")
    public InvoiceResponse payInvoice(
            @Parameter(description = "Invoice UUID", required = true) @PathVariable UUID invoiceId,
            @Valid @RequestBody PayInvoiceRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.payInvoice(invoiceId, request, principal.getUser().getId());
    }

    @Operation(summary = "List invoices", description = "Lists a card's invoices, newest reference month first, with a lightweight item count per invoice — no charges.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoices returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Credit card not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/invoices")
    @PreAuthorize("isAuthenticated()")
    public Page<InvoiceSummaryResponse> listInvoices(
            @Parameter(description = "Credit card UUID", required = true) @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return invoiceManagementService.listInvoices(id, principal.getUser().getId(), page, size);
    }

    @Operation(summary = "Get invoice by id", description = "Same shape as GET /{id}/invoices/{referenceMonth}, but addressed by invoice id — the invoice detail screen navigates by id, not by card + month.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/invoices/{invoiceId}")
    @PreAuthorize("isAuthenticated()")
    public InvoiceResponse getInvoiceById(
            @Parameter(description = "Invoice UUID", required = true) @PathVariable UUID invoiceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return invoiceManagementService.getInvoiceById(invoiceId, principal.getUser().getId(), page, size);
    }

    @Operation(summary = "Update invoice item", description = """
            Corrects the description and/or category of a single invoice item, after it was already \
            imported (or recorded by hand). Mirrors the change onto the linked transaction, if any. \
            `rememberMerchant` saves the description as this merchant's remembered alias, so the next \
            import comes pre-filled. `applyToHistory` applies the same description and category to the \
            merchant's other non-cancelled items across every invoice.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Item updated"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice item or category not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Item is cancelled",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/invoices/items/{itemId}")
    @PreAuthorize("isAuthenticated()")
    public UpdateInvoiceItemResponse updateInvoiceItem(
            @Parameter(description = "Invoice item UUID", required = true) @PathVariable UUID itemId,
            @Valid @RequestBody UpdateInvoiceItemRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return invoiceManagementService.updateItem(itemId, request, principal.getUser().getId());
    }

    @Operation(summary = "Get merchant scope", description = "How many other non-cancelled items share this item's merchant, and the merchant's currently remembered alias, if any — what the edit dialog needs before showing its \"apply to N others\" checkbox.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Merchant scope returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice item not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/invoices/items/{itemId}/merchant")
    @PreAuthorize("isAuthenticated()")
    public MerchantScopeResponse getMerchantScope(
            @Parameter(description = "Invoice item UUID", required = true) @PathVariable UUID itemId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return invoiceManagementService.getMerchantScope(itemId, principal.getUser().getId());
    }

    @Operation(summary = "Settle invoice without a transaction", description = """
            Marks the invoice PAID without creating a payment transaction on any account — the same \
            simple settlement the import's "already paid" checkbox performs, for a bill paid outside \
            the app before it was ever imported. Charges stay PENDING and the account balance is \
            untouched. Reversible via POST .../reopen, as long as no real payment (POST .../pay) \
            happened since.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice settled"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/invoices/{invoiceId}/settle")
    @PreAuthorize("isAuthenticated()")
    public InvoiceResponse settleInvoice(
            @Parameter(description = "Invoice UUID", required = true) @PathVariable UUID invoiceId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return invoiceManagementService.settle(invoiceId, principal.getUser().getId());
    }

    @Operation(summary = "Reopen a settled invoice", description = "Undoes POST .../settle. Refused when the invoice was paid through a real transaction (POST .../pay) instead — reopening it would leave that transaction orphaned.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice reopened"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invoice not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Invoice is not paid, or was paid through a real transaction",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/invoices/{invoiceId}/reopen")
    @PreAuthorize("isAuthenticated()")
    public InvoiceResponse reopenInvoice(
            @Parameter(description = "Invoice UUID", required = true) @PathVariable UUID invoiceId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return invoiceManagementService.reopen(invoiceId, principal.getUser().getId());
    }

    @Operation(summary = "Get limit usage", description = "Returns real-time credit limit usage for the card, accounting for all open, closed, partial, and overdue invoices.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Limit usage returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Card not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/limit")
    @PreAuthorize("isAuthenticated()")
    public LimitUsageResponse getLimitUsage(
            @Parameter(description = "Credit card UUID", required = true) @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.getLimitUsage(id, principal.getUser().getId());
    }

    @Operation(summary = "Get spending by category", description = "Returns aggregated spending by category for the card, optionally filtered by date range.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Spending breakdown returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid JWT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Card not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}/spending")
    @PreAuthorize("isAuthenticated()")
    public List<SpendingByCategoryResponse> getSpendingByCategory(
            @Parameter(description = "Credit card UUID", required = true) @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return creditCardService.getSpendingByCategory(id, from, to, principal.getUser().getId());
    }
}
