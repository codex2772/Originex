package com.originex.starter.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InboxEventRepository extends JpaRepository<InboxEventJpaEntity, UUID> {
}
