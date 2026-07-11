package com.originex.lms.application.service;

import com.originex.common.tenant.SystemContextHolder;
import com.originex.lms.application.port.out.LoanRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Proves the daily DPD/NPA aging sweep runs its cross-tenant scan in
 * {@link SystemContextHolder system context} (so it routes to the BYPASSRLS role
 * when RLS is enabled) and that the context is always cleared afterward — on both
 * the normal and the exception path. See dev/RLS_DESIGN.md §5, §7.2.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DpdAgingService — system-context lifecycle")
class DpdAgingServiceTest {

    @Mock
    private LoanRepository loanRepository;
    @Mock
    private DpdAgingProcessor processor;

    private DpdAgingService service() {
        return new DpdAgingService(loanRepository, processor, 500, "Asia/Kolkata");
    }

    @AfterEach
    void clearContext() {
        SystemContextHolder.exit();
    }

    @Test
    @DisplayName("scan runs in system context and it is cleared after a normal run")
    void entersSystemContextDuringScanAndClearsAfter() {
        AtomicBoolean systemContextDuringScan = new AtomicBoolean(false);
        when(loanRepository.findDelinquent(any(), any(), anyInt()))
                .thenAnswer(inv -> {
                    systemContextDuringScan.set(SystemContextHolder.isSystemContext());
                    return List.of(); // empty → loop exits immediately
                });

        assertThat(SystemContextHolder.isSystemContext()).isFalse();
        service().runDailyAging();

        assertThat(systemContextDuringScan).as("scan must run in system context").isTrue();
        assertThat(SystemContextHolder.isSystemContext())
                .as("system context must be cleared after the run").isFalse();
    }

    @Test
    @DisplayName("system context is cleared in finally even when the scan throws")
    void clearsSystemContextWhenScanThrows() {
        when(loanRepository.findDelinquent(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("scan boom"));

        assertThatThrownBy(() -> service().runDailyAging())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("scan boom");

        assertThat(SystemContextHolder.isSystemContext())
                .as("system context must be cleared even on failure").isFalse();
    }
}
