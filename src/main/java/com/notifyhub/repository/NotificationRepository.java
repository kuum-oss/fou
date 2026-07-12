package com.notifyhub.repository;

import com.notifyhub.domain.Notification;
import com.notifyhub.domain.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for Notification entities.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Finds all notifications with the given status (used by the retry scheduler).
     *
     * @param status the notification status
     * @return list of notifications with pre-fetched orders
     */
    @Query("""
            SELECT n FROM Notification n
            JOIN FETCH n.order o
            WHERE n.status = :status
            """)
    List<Notification> findAllByStatusWithOrder(@Param("status") NotificationStatus status);

    /**
     * Finds all notifications for a specific order.
     *
     * @param orderId the order UUID
     * @return list of notifications
     */
    List<Notification> findByOrderId(UUID orderId);
}
