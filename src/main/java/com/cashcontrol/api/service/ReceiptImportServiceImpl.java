package com.cashcontrol.api.service;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.domain.entity.Account;
import com.cashcontrol.api.domain.entity.Category;
import com.cashcontrol.api.domain.entity.CategoryRule;
import com.cashcontrol.api.domain.entity.PaymentMethod;
import com.cashcontrol.api.domain.entity.PaymentMethodSlug;
import com.cashcontrol.api.domain.entity.Transaction;
import com.cashcontrol.api.domain.entity.TransactionStatus;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.dto.request.ReceiptCommitRequest;
import com.cashcontrol.api.dto.response.ReceiptPreviewResponse;
import com.cashcontrol.api.dto.response.TransactionDetailResponse;
import com.cashcontrol.api.repository.AccountRepository;
import com.cashcontrol.api.repository.CategoryRepository;
import com.cashcontrol.api.repository.CategoryRuleRepository;
import com.cashcontrol.api.repository.PaymentMethodRepository;
import com.cashcontrol.api.repository.TransactionRepository;
import com.cashcontrol.api.service.receipt.ParsedReceipt;
import com.cashcontrol.api.service.receipt.PixReceiptParser;
import com.cashcontrol.api.service.receipt.ReceiptTextExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lê um comprovante de PIX compartilhado e o transforma numa transação, em dois passos —
 * mesma forma de {@code StatementImportServiceImpl}/{@code FaturaImportServiceImpl}: uma
 * prévia que nada grava, e uma confirmação com o que o usuário revisou.
 *
 * <p>A diferença de fundo para as duas outras importações é que aqui não existe formato
 * declarado nem "arquivo com várias linhas": o comprovante é sempre uma linha só, lida por
 * um parser heurístico único ({@link PixReceiptParser}) a partir do texto que
 * {@link ReceiptTextExtractor} conseguir tirar do arquivo — PDF hoje, imagem via OCR
 * quando habilitado. Por isso o resultado é sempre tratado como melhor esforço: o fluxo
 * pede revisão do usuário antes de gravar, nunca confia cegamente no que foi lido.
 */
@Service
@RequiredArgsConstructor
public class ReceiptImportServiceImpl implements ReceiptImportService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CategorySuggester categorySuggester;
    private final MerchantAliasService merchantAliasService;
    private final TransactionService transactionService;
    private final AttachmentService attachmentService;
    private final List<ReceiptTextExtractor> extractors;
    private final PixReceiptParser pixReceiptParser;
    private final AppProperties appProperties;

    @Override
    @Transactional(readOnly = true)
    public ReceiptPreviewResponse preview(MultipartFile file, UUID accountId, UUID userId) {
        validateFile(file);

        String mimeType = file.getContentType();
        Optional<ReceiptTextExtractor> extractor = extractors.stream()
                .filter(candidate -> candidate.supports(mimeType))
                .findFirst();

        ParsedReceipt parsed = extractor.map(e -> pixReceiptParser.parseText(e.extract(file)))
                .orElse(ParsedReceipt.EMPTY);

        String externalRef = parsed.endToEndId() != null
                ? parsed.endToEndId()
                : hashOf(parsed);

        boolean checkDuplicate = accountId != null && externalRef != null;
        Transaction existing = checkDuplicate
                ? transactionRepository.findAllByExternalRefIn(userId, accountId, List.of(externalRef))
                        .stream().findFirst().orElse(null)
                : null;

        String recipientName = parsed.recipientName();
        CategorySuggester.Suggestion suggestion = CategorySuggester.Suggestion.NONE;
        String suggestedDescription = null;
        if (recipientName != null) {
            List<CategoryRule> rules = categoryRuleRepository.findAllByUserIdAndIsActiveTrueOrderByPriorityAsc(userId);
            CategorySuggester.History history = categorySuggester.loadHistory(userId, List.of(recipientName));
            suggestion = categorySuggester.suggest(recipientName, rules, history);
            suggestedDescription = merchantAliasService.suggest(recipientName, merchantAliasService.load(userId));
        }

        return new ReceiptPreviewResponse(
                externalRef,
                parsed.amount(),
                parsed.date(),
                recipientName,
                parsed.recipientDocument(),
                parsed.institution(),
                parsed.message(),
                recipientName != null ? MerchantKey.of(recipientName) : null,
                suggestedDescription,
                suggestion.categoryId(),
                suggestion.categoryName(),
                suggestion.subcategoryId(),
                suggestion.subcategoryName(),
                suggestion.source(),
                existing != null,
                existing != null ? existing.getId() : null,
                parsed.unreadFields(),
                extractor.isEmpty() || parsed.isEmpty());
    }

    @Override
    @Transactional
    public TransactionDetailResponse commit(ReceiptCommitRequest request, MultipartFile file, UUID userId) {
        Account account = accountRepository.findByIdAndUserIdAndDeletedAtIsNull(request.accountId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + request.accountId()));
        if (account.getArchivedAt() != null) {
            throw new BusinessRuleException("Não é possível lançar um comprovante em uma conta arquivada.");
        }

        boolean alreadyExists = !transactionRepository
                .findAllByExternalRefIn(userId, account.getId(), List.of(request.externalRef()))
                .isEmpty();
        if (alreadyExists) {
            throw new ConflictException("Este comprovante já foi lançado.");
        }

        PaymentMethod paymentMethod = paymentMethodRepository.findBySlug(PaymentMethodSlug.PIX)
                .orElseThrow(() -> new BusinessRuleException("Forma de pagamento PIX não cadastrada."));

        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAccount(account);
        tx.setType(request.type());
        tx.setAmount(request.amount());
        tx.setDescription(request.description());
        tx.setCompetenceDate(request.competenceDate());
        tx.setPaymentMethod(paymentMethod);
        tx.setExternalRef(request.externalRef());

        TransactionStatus status = request.status() != null ? request.status() : TransactionStatus.PAID;
        tx.setStatus(status);
        tx.setPaymentDate(status == TransactionStatus.PAID ? request.competenceDate() : null);

        if (request.categoryId() != null) {
            Category category = categoryRepository.findByIdVisibleToUser(request.categoryId(), userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));
            tx.setCategory(category);

            if (request.subcategoryId() != null) {
                Category subcategory = categoryRepository.findByIdVisibleToUser(request.subcategoryId(), userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.subcategoryId()));
                tx.setSubcategory(subcategory);
            }
        }

        tx = transactionRepository.save(tx);

        // Antes do anexo: uma transação que falhasse ao anexar o comprovante não pode ficar
        // gravada sem o arquivo que a originou — a exceção do attach reverte tudo.
        attachmentService.attach(tx.getId(), new MultipartFile[]{file}, userId);

        merchantAliasService.remember(userId, request.originalDescription(), request.description());

        return transactionService.toDetail(tx);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Envie o comprovante.");
        }
        long maxBytes = (long) appProperties.getReceiptImport().getMaxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BusinessRuleException("O arquivo excede o tamanho máximo permitido de "
                                            + appProperties.getReceiptImport().getMaxFileSizeMb() + " MB.");
        }
    }

    /**
     * Chave de deduplicação para quando o comprovante não traz o endToEndId — nem todo
     * banco imprime o identificador fim-a-fim de forma legível na extração de texto.
     * SHA-256 de data|valor|destinatário: os três juntos raramente coincidem em dois PIX
     * diferentes, e o hash é estável entre duas leituras do mesmo arquivo.
     *
     * @return {@code null} quando nem valor, nem data, nem destinatário foram lidos —
     *         não há identidade nenhuma para hashear, e a duplicata fica sem checagem
     *         possível até o usuário preencher os campos na revisão
     */
    private String hashOf(ParsedReceipt parsed) {
        if (parsed.amount() == null && parsed.date() == null && parsed.recipientName() == null) {
            return null;
        }
        String identity = parsed.date() + "|" + parsed.amount() + "|"
                + (parsed.recipientName() != null ? parsed.recipientName().trim().toLowerCase() : "");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        }
    }
}
