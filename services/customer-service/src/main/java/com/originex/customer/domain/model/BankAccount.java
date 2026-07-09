package com.originex.customer.domain.model;

import java.util.UUID;

/**
 * Bank account value object within Customer aggregate.
 */
public class BankAccount {

    private UUID bankAccountId;
    private String accountNumber;   // Encrypted
    private String accountNumberMasked;  // XXXXXXXX5678
    private String ifscCode;
    private String bankName;
    private String accountHolderName;
    private BankAccountType accountType;
    private boolean verified;
    private boolean primary;

    public static BankAccount create(String accountNumber, String ifscCode,
                                     String bankName, String accountHolderName,
                                     BankAccountType accountType) {
        BankAccount ba = new BankAccount();
        ba.bankAccountId = UUID.randomUUID();
        ba.accountNumber = accountNumber;
        ba.accountNumberMasked = maskAccountNumber(accountNumber);
        ba.ifscCode = ifscCode;
        ba.bankName = bankName;
        ba.accountHolderName = accountHolderName;
        ba.accountType = accountType;
        ba.verified = false;
        ba.primary = false;
        return ba;
    }

    public void markVerified() { this.verified = true; }
    public void markPrimary() { this.primary = true; }
    public void unsetPrimary() { this.primary = false; }
    public boolean isPrimary() { return primary; }

    private static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) return "****";
        return "X".repeat(accountNumber.length() - 4) + accountNumber.substring(accountNumber.length() - 4);
    }

    // Accessors
    public UUID getBankAccountId() { return bankAccountId; }
    public String getAccountNumber() { return accountNumber; }
    public String getAccountNumberMasked() { return accountNumberMasked; }
    public String getIfscCode() { return ifscCode; }
    public String getBankName() { return bankName; }
    public String getAccountHolderName() { return accountHolderName; }
    public BankAccountType getAccountType() { return accountType; }
    public boolean isVerified() { return verified; }

    public BankAccount() {}
    public void setBankAccountId(UUID id) { this.bankAccountId = id; }
    public void setAccountNumber(String s) { this.accountNumber = s; }
    public void setAccountNumberMasked(String s) { this.accountNumberMasked = s; }
    public void setIfscCode(String s) { this.ifscCode = s; }
    public void setBankName(String s) { this.bankName = s; }
    public void setAccountHolderName(String s) { this.accountHolderName = s; }
    public void setAccountType(BankAccountType t) { this.accountType = t; }
    public void setVerified(boolean b) { this.verified = b; }
    public void setPrimary(boolean b) { this.primary = b; }

    public enum BankAccountType { SAVINGS, CURRENT }
}
