package com.originex.common.money;

/**
 * Thrown when an arithmetic operation is attempted between Money objects of different currencies.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String message) {
        super(message);
    }
}
