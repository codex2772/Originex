package com.originex.customer.application.service;

import com.originex.customer.application.port.in.CustomerUseCase.RegisterCustomerCommand;
import com.originex.customer.application.port.out.AadhaarVerificationPort;
import com.originex.customer.application.port.out.BankAccountVerificationPort;
import com.originex.customer.application.port.out.CustomerRepository;
import com.originex.customer.application.port.out.PanVerificationPort;
import com.originex.customer.domain.model.Customer;
import com.originex.starter.outbox.OutboxPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CustomerApplicationService#registerCustomer}, focused on
 * the PAN-verification referenceId contract.
 *
 * <p>Regression guard: the referenceId sent to the Partner Integration Service
 * must be the newly minted customerId — never {@code null}. Historically the
 * verify call was sequenced <i>before</i> the aggregate was created, so a literal
 * {@code null} went out and the partner's {@code @NotBlank} rejected it (422),
 * which the circuit-breaker fallback then masked as "temporarily unavailable".
 *
 * <p>Hand-authored fakes (no Mockito) — inline mocking is unreliable on the local
 * JDK, and capturing fakes read more clearly for this contract anyway.
 */
@DisplayName("CustomerApplicationService.registerCustomer — PAN referenceId contract")
class CustomerApplicationServiceTest {

    private final CapturingPanPort panPort = new CapturingPanPort();
    private final RecordingCustomerRepository repository = new RecordingCustomerRepository();
    private final CustomerApplicationService service = new CustomerApplicationService(
            repository, new NoopOutboxPublisher(), panPort,
            new UnusedAadhaarPort(), new UnusedBankPort());

    private static RegisterCustomerCommand commandWithPan() {
        return new RegisterCustomerCommand(
                UUID.randomUUID(), "Alice", "Borrower", "alice@example.com",
                "9999999999", LocalDate.of(1990, 1, 1), "ABCDE1234F", "123412341234");
    }

    @Test
    @DisplayName("referenceId is the saved customerId — non-null, non-blank")
    void referenceIdIsSavedCustomerId() {
        panPort.result = new PanVerificationPort.PanVerificationResult(
                true, "ALICE BORROWER", "VALID", true, null);

        Customer saved = service.registerCustomer(commandWithPan());

        assertThat(panPort.lastRequest).isNotNull();
        assertThat(panPort.lastRequest.referenceId())
                .isNotBlank()
                .isEqualTo(saved.getCustomerId().toString());
    }

    @Test
    @DisplayName("PAN verification failure throws and nothing is persisted")
    void panFailureThrowsAndDoesNotPersist() {
        panPort.result = new PanVerificationPort.PanVerificationResult(
                false, null, "INVALID", false, "PAN not found");

        assertThatThrownBy(() -> service.registerCustomer(commandWithPan()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAN verification failed");

        assertThat(repository.saved).isNull();
    }

    // ─── Fakes ───

    static final class CapturingPanPort implements PanVerificationPort {
        PanVerificationRequest lastRequest;
        PanVerificationResult result;

        @Override
        public PanVerificationResult verify(PanVerificationRequest request) {
            this.lastRequest = request;
            return result;
        }
    }

    static final class RecordingCustomerRepository implements CustomerRepository {
        Customer saved;

        @Override public Customer save(Customer customer) { this.saved = customer; return customer; }
        @Override public Optional<Customer> findById(UUID tenantId, UUID customerId) { return Optional.ofNullable(saved); }
        @Override public Optional<Customer> findByPhone(UUID tenantId, String phone) { return Optional.empty(); }
        @Override public boolean existsByPanHash(UUID tenantId, String panHash) { return false; }
        @Override public boolean existsByPhone(UUID tenantId, String phone) { return false; }
    }

    static final class NoopOutboxPublisher extends OutboxPublisher {
        NoopOutboxPublisher() { super(null, null); }
        @Override public void publish(String aggregateType, UUID aggregateId,
                                      String eventType, UUID tenantId, byte[] payload) { /* no-op */ }
    }

    static final class UnusedAadhaarPort implements AadhaarVerificationPort {
        @Override public AadhaarVerificationResult verify(AadhaarVerificationRequest request) {
            throw new AssertionError("Aadhaar verification must not be called during registerCustomer");
        }
    }

    static final class UnusedBankPort implements BankAccountVerificationPort {
        @Override public BankAccountVerificationResult verify(BankAccountVerificationRequest request) {
            throw new AssertionError("Bank verification must not be called during registerCustomer");
        }
    }
}
