package com.originex.lms.application.service;

import com.originex.common.exception.ResourceNotFoundException;
import com.originex.lms.application.port.out.LoanRepository;
import com.originex.lms.domain.exception.LoanNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the #4 fix: a missing loan now surfaces as <b>404</b>, not 400.
 *
 * <p>Previously {@code getLoan} threw a bare {@code IllegalArgumentException}, which the platform's
 * {@code GlobalExceptionHandler} maps to <b>400 Bad Request</b> — the same status it returns for genuine
 * input-validation failures (e.g. "Payment must be positive"). A missing resource is not a bad request.
 * The fix throws a distinct {@link LoanNotFoundException}; this test pins both halves of why that yields a
 * 404: it is thrown for a miss, and it <b>is-a</b> {@link ResourceNotFoundException}, the type the shared
 * handler maps to 404. Validation's {@code IllegalArgumentException} is deliberately left on its own
 * 400 path, so the two cases no longer collide.
 */
@DisplayName("#4 — a missing loan is a 404 (LoanNotFoundException), not a 400")
class LoanNotFoundMappingTest {

    @Test
    @DisplayName("getLoan throws LoanNotFoundException when the row is absent")
    void getLoanThrowsNotFoundForMissingRow() {
        LoanRepository repo = mock(LoanRepository.class);
        when(repo.findById(any(), any())).thenReturn(Optional.empty());
        // getLoan reads only the repository; the outbox publisher is never touched on this path, so it is
        // left null rather than mocked (mocking the concrete OutboxPublisher trips Mockito's inline maker
        // on newer JVMs — and there is nothing to stub anyway).
        LoanApplicationServiceImpl service = new LoanApplicationServiceImpl(repo, null);

        assertThatThrownBy(() -> service.getLoan(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(LoanNotFoundException.class);
    }

    @Test
    @DisplayName("LoanNotFoundException is a ResourceNotFoundException — the type mapped to 404")
    void notFoundExceptionMapsTo404ViaPlatformHandler() {
        assertThat(new LoanNotFoundException(UUID.randomUUID()))
                .as("must extend ResourceNotFoundException so GlobalExceptionHandler returns 404, not the "
                        + "catch-all 500 or the IllegalArgument 400")
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
