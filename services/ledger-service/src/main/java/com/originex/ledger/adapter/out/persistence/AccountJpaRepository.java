package com.originex.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, UUID> {

    @Query("SELECT a FROM AccountJpaEntity a WHERE a.tenantId = :tenantId AND a.accountId = :accountId")
    Optional<AccountJpaEntity> findByTenantAndId(UUID tenantId, UUID accountId);

    @Query("SELECT a FROM AccountJpaEntity a WHERE a.tenantId = :tenantId AND a.accountNumber = :accountNumber")
    Optional<AccountJpaEntity> findByTenantAndAccountNumber(UUID tenantId, String accountNumber);
}
