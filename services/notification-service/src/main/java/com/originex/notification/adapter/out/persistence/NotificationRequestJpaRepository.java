package com.originex.notification.adapter.out.persistence;

import com.originex.notification.domain.model.NotificationChannel;
import com.originex.notification.domain.model.NotificationTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRequestJpaRepository extends JpaRepository<NotificationRequestJpaEntity, UUID> {

    boolean existsBySourceEventId(String sourceEventId);

    @Query("SELECT n FROM NotificationRequestJpaEntity n WHERE n.tenantId = :tid AND n.notificationId = :id")
    Optional<NotificationRequestJpaEntity> findByTenantAndId(UUID tid, UUID id);

    @Query("SELECT n FROM NotificationRequestJpaEntity n WHERE n.status = 'FAILED' AND n.retryCount < 3 ORDER BY n.updatedAt ASC")
    List<NotificationRequestJpaEntity> findPendingRetries(org.springframework.data.domain.Pageable pageable);
}
