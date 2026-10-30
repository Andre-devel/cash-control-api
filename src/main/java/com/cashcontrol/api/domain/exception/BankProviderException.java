package com.cashcontrol.api.domain.exception;

/** O provedor de Open Finance (Pluggy) falhou ou respondeu algo que não dá para usar. */
public class BankProviderException extends AuthException {

    public BankProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
