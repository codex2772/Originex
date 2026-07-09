package com.originex.ledger.adapter.out.persistence;

import com.originex.ledger.application.port.out.AccountRepository;
import com.originex.ledger.domain.model.Account;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class AccountPersistenceAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;

    public AccountPersistenceAdapter(AccountJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Account save(Account account) {
        AccountJpaEntity entity = AccountJpaEntity.fromDomain(account);
        jpaRepository.save(entity);
        return account; // Return domain object directly (already mutated)
    }

    @Override
    public Optional<Account> findById(UUID tenantId, UUID accountId) {
        return jpaRepository.findByTenantAndId(tenantId, accountId)
                .map(AccountJpaEntity::toDomain);
    }

    @Override
    public Optional<Account> findByAccountNumber(UUID tenantId, String accountNumber) {
        return jpaRepository.findByTenantAndAccountNumber(tenantId, accountNumber)
                .map(AccountJpaEntity::toDomain);
    }
}
