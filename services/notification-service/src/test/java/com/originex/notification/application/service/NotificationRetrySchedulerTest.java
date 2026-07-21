package com.originex.notification.application.service;

import com.originex.common.tenant.SystemContextHolder;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * Proves {@link NotificationApplicationService#retryFailedJob()} enters
 * {@link SystemContextHolder system context} *before* delegating to the
 * transactional {@code retryFailed()} (so the retry sweep routes to the BYPASSRLS
 * role when RLS is enabled) and always clears it afterward — on both the normal
 * and the exception path. See dev/RLS_DESIGN.md §5, §7.2.
 *
 * <p>The scheduler delegates through the injected Spring proxy ({@code self});
 * here that proxy is a mock, letting us observe the context in force at the moment
 * {@code retryFailed()} would open its transaction without needing a database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationApplicationService — retry scheduler system-context lifecycle")
class NotificationRetrySchedulerTest {

    @Mock
    private NotificationApplicationService self;

    private NotificationApplicationService scheduler() {
        return new NotificationApplicationService(null, null, List.of(), null, self);
    }

    @AfterEach
    void clearContext() {
        SystemContextHolder.exit();
    }

    @Test
    @DisplayName("retryFailed() is invoked in system context and it is cleared after")
    void delegatesInSystemContextAndClearsAfter() {
        AtomicBoolean systemContextAtDelegation = new AtomicBoolean(false);
        doAnswer(inv -> {
            systemContextAtDelegation.set(SystemContextHolder.isSystemContext());
            return null;
        }).when(self).retryFailed();

        assertThat(SystemContextHolder.isSystemContext()).isFalse();
        scheduler().retryFailedJob();

        assertThat(systemContextAtDelegation)
                .as("retryFailed() must run in system context").isTrue();
        assertThat(SystemContextHolder.isSystemContext())
                .as("system context must be cleared after the job").isFalse();
    }

    @Test
    @DisplayName("system context is cleared in finally even when retryFailed() throws")
    void clearsSystemContextWhenRetryThrows() {
        doThrow(new RuntimeException("retry boom")).when(self).retryFailed();

        assertThatThrownBy(() -> scheduler().retryFailedJob())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("retry boom");

        assertThat(SystemContextHolder.isSystemContext())
                .as("system context must be cleared even on failure").isFalse();
    }
}
