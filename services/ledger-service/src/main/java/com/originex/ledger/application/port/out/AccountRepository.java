package com.originex.ledger.application.port.out;

import com.originex.ledger.domain.model.Account;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findById(UUID tenantId, UUID accountId);

    Optional<Account> findByAccountNumber(UUID tenantId, String accountNumber);
}
