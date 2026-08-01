package com.cashcontrol.api.controller;

import com.cashcontrol.api.domain.exception.AuthException;
import com.cashcontrol.api.domain.exception.BusinessRuleException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.ForbiddenAccessException;
import com.cashcontrol.api.domain.exception.InvalidCredentialsException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import com.cashcontrol.api.dto.response.ErrorResponse;
import com.cashcontrol.api.security.CorrelationIdHolder;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Valor inválido",
                        (first, second) -> first
                ));
        UUID correlationId = CorrelationIdHolder.get();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.withFieldErrors(
                        "VALIDATION_ERROR", "Falha na validação da requisição.", correlationId, fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        cv -> cv.getPropertyPath().toString(),
                        cv -> cv.getMessage() != null ? cv.getMessage() : "Valor inválido",
                        (first, second) -> first
                ));
        UUID correlationId = CorrelationIdHolder.get();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.withFieldErrors(
                        "VALIDATION_ERROR", "Violação de restrição.", correlationId, fieldErrors));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("UNAUTHORIZED", "Credenciais inválidas.", ex.getCorrelationId()));
    }

    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTokenExpired(TokenExpiredException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("TOKEN_EXPIRED", "O token expirou.", ex.getCorrelationId()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", "Recurso não encontrado.", ex.getCorrelationId()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONFLICT", ex.getMessage(), ex.getCorrelationId()));
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("BUSINESS_RULE_VIOLATION", ex.getMessage(), ex.getCorrelationId()));
    }

    @ExceptionHandler(ForbiddenAccessException.class)
    public ResponseEntity<ErrorResponse> handleForbiddenAccess(ForbiddenAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", ex.getMessage(), ex.getCorrelationId()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        UUID correlationId = CorrelationIdHolder.get();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", "Acesso negado.", correlationId));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("UNAUTHORIZED", "Falha na autenticação.", ex.getCorrelationId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParam(MissingServletRequestParameterException ex) {
        UUID correlationId = CorrelationIdHolder.get();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MISSING_PARAMETER",
                        "O parâmetro obrigatório '" + ex.getParameterName() + "' não foi informado.", correlationId));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        UUID correlationId = CorrelationIdHolder.get();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("INVALID_PARAMETER",
                        "Valor inválido para o parâmetro '" + ex.getName() + "'.", correlationId));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        UUID correlationId = CorrelationIdHolder.get();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("BAD_REQUEST", "Corpo da requisição malformado ou ilegível.", correlationId));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        UUID correlationId = CorrelationIdHolder.get();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ErrorResponse.of("METHOD_NOT_ALLOWED", "Método não permitido.", correlationId));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        UUID correlationId = CorrelationIdHolder.get();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", "Recurso não encontrado.", correlationId));
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleErrorResponseException(ErrorResponseException ex) {
        UUID correlationId = CorrelationIdHolder.get();
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.name(), reasonPhrasePt(status), correlationId));
    }

    // HttpStatus.getReasonPhrase() é sempre em inglês; a API responde em pt-BR.
    private static String reasonPhrasePt(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Requisição inválida.";
            case UNAUTHORIZED -> "Não autenticado.";
            case FORBIDDEN -> "Acesso negado.";
            case NOT_FOUND -> "Recurso não encontrado.";
            case METHOD_NOT_ALLOWED -> "Método não permitido.";
            case NOT_ACCEPTABLE -> "Formato de resposta não suportado.";
            case CONFLICT -> "Conflito com o estado atual do recurso.";
            case PAYLOAD_TOO_LARGE -> "Requisição maior que o limite permitido.";
            case UNSUPPORTED_MEDIA_TYPE -> "Tipo de conteúdo não suportado.";
            case UNPROCESSABLE_ENTITY -> "Regra de negócio violada.";
            case TOO_MANY_REQUESTS -> "Muitas requisições. Tente novamente em instantes.";
            case SERVICE_UNAVAILABLE -> "Serviço temporariamente indisponível.";
            default -> "Ocorreu um erro inesperado.";
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception ex) {
        UUID correlationId = CorrelationIdHolder.get();
        log.error("Unhandled exception [correlationId={}]", correlationId, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "Ocorreu um erro inesperado.", correlationId));
    }
}
