package dev.starsupply.integration;

/** The same receipt-backed tax request may be retried; this is never nonpayment evidence. */
public final class TaxFundingRetryException extends RuntimeException {
    public TaxFundingRetryException(String message, Throwable cause) { super(message, cause); }
}
