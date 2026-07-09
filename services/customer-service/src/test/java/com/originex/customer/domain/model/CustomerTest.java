package com.originex.customer.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Customer Aggregate")
class CustomerTest {

    private static final UUID TENANT = UUID.randomUUID();

    private Customer createRegisteredCustomer() {
        return Customer.register(TENANT, "Rahul", "Sharma",
                "rahul@example.com", "9876543210", LocalDate.of(1990, 5, 15));
    }

    @Nested
    @DisplayName("Registration")
    class Registration {

        @Test
        void shouldRegisterWithCorrectState() {
            Customer c = createRegisteredCustomer();
            assertThat(c.getStatus()).isEqualTo(CustomerStatus.REGISTERED);
            assertThat(c.getKycStatus()).isEqualTo(KycStatus.NOT_INITIATED);
            assertThat(c.getFullName()).isEqualTo("Rahul Sharma");
            assertThat(c.isKycVerified()).isFalse();
            assertThat(c.isEligibleForLoan()).isFalse();
        }

        @Test
        void shouldRejectBlankName() {
            assertThatThrownBy(() -> Customer.register(TENANT, "", "Sharma",
                    null, "9999999999", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("KYC Lifecycle")
    class KYCLifecycle {

        @Test
        void shouldCompleteKYCAndBecomeActive() {
            Customer c = createRegisteredCustomer();

            KycRecord record = KycRecord.create(KycRecord.KycType.VIDEO_KYC, "VID-REF-001");
            c.submitKyc(record);
            assertThat(c.getKycStatus()).isEqualTo(KycStatus.PENDING);

            c.completeKyc(record.getKycRecordId());
            assertThat(c.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
            assertThat(c.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
            assertThat(c.isEligibleForLoan()).isTrue();
        }

        @Test
        void shouldRejectKYC() {
            Customer c = createRegisteredCustomer();
            KycRecord record = KycRecord.create(KycRecord.KycType.EKYC_AADHAAR, "REF-002");
            c.submitKyc(record);

            c.rejectKyc(record.getKycRecordId(), "Blurry document");
            assertThat(c.getKycStatus()).isEqualTo(KycStatus.REJECTED);
            assertThat(c.isEligibleForLoan()).isFalse();
        }
    }

    @Nested
    @DisplayName("Bank Accounts")
    class BankAccounts {

        @Test
        void shouldSetFirstAccountAsPrimary() {
            Customer c = createRegisteredCustomer();
            BankAccount ba = BankAccount.create("1234567890", "SBIN0001234",
                    "SBI", "Rahul Sharma", BankAccount.BankAccountType.SAVINGS);

            c.addBankAccount(ba);
            assertThat(c.getBankAccounts()).hasSize(1);
            assertThat(c.getBankAccounts().get(0).isPrimary()).isTrue();
        }

        @Test
        void shouldNotAllowAccountsOnBlockedCustomer() {
            Customer c = createRegisteredCustomer();
            c.block("Fraud suspect");

            BankAccount ba = BankAccount.create("9876543210", "HDFC0001111",
                    "HDFC", "Rahul Sharma", BankAccount.BankAccountType.CURRENT);

            assertThatThrownBy(() -> c.addBankAccount(ba))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("blocked");
        }
    }

    @Nested
    @DisplayName("Blocking")
    class Blocking {

        @Test
        void shouldBlockAndPreventOperations() {
            Customer c = createRegisteredCustomer();
            c.block("Suspicious activity");
            assertThat(c.getStatus()).isEqualTo(CustomerStatus.BLOCKED);

            assertThatThrownBy(() -> c.updateProfile("New", "Name", null, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
