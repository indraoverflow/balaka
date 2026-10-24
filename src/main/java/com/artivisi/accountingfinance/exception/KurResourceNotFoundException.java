package com.artivisi.accountingfinance.exception;

/**
 * A referenced KUR loan, installment or payment does not exist. Kept as an
 * {@link IllegalArgumentException} so the existing read endpoints, which map that
 * type to {@code 404 kur_loan_not_found}, keep their contract; the write API maps
 * this subtype to its own {@code 404}.
 */
public class KurResourceNotFoundException extends IllegalArgumentException {

    public KurResourceNotFoundException(String message) {
        super(message);
    }
}
