package com.originex.common.security;

import com.originex.common.security.SubjectContext.PrincipalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The v1 principal model: a {@link SubjectContext} is exactly one of human user,
 * customer (subject-scoped), or service account.
 */
@DisplayName("SubjectContext — principal model")
class SubjectContextTest {

    @Test
    @DisplayName("human user: HUMAN_USER, not machine, not customer-scoped")
    void humanUser() {
        SubjectContext s = SubjectContext.user("user-1");
        assertThat(s.type()).isEqualTo(PrincipalType.HUMAN_USER);
        assertThat(s.subject()).isEqualTo("user-1");
        assertThat(s.customerId()).isNull();
        assertThat(s.isHuman()).isTrue();
        assertThat(s.isMachine()).isFalse();
        assertThat(s.isCustomerScoped()).isFalse();
    }

    @Test
    @DisplayName("customer: CUSTOMER, subject-scoped to its customerId")
    void customer() {
        SubjectContext s = SubjectContext.customer("user-2", "cust-9");
        assertThat(s.type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(s.customerId()).isEqualTo("cust-9");
        assertThat(s.isCustomerScoped()).isTrue();
        assertThat(s.isHuman()).isTrue();
        assertThat(s.isMachine()).isFalse();
    }

    @Test
    @DisplayName("service account: SERVICE_ACCOUNT keyed by client id, is machine")
    void serviceAccount() {
        SubjectContext s = SubjectContext.serviceAccount("svc-los");
        assertThat(s.type()).isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(s.subject()).isEqualTo("svc-los");
        assertThat(s.customerId()).isNull();
        assertThat(s.isMachine()).isTrue();
        assertThat(s.isHuman()).isFalse();
        assertThat(s.isCustomerScoped()).isFalse();
    }

    @Test
    @DisplayName("a customer principal without a customerId is rejected")
    void customerRequiresCustomerId() {
        assertThatThrownBy(() -> new SubjectContext(PrincipalType.CUSTOMER, "user-2", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");
    }
}
