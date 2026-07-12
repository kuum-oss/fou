package com.notifyhub.repository;

import com.notifyhub.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Order entities.
 *
 * <p>The custom JPQL query uses JOIN FETCH to load notifications alongside orders
 * in a single SQL query — this is the N+1 solution.</p>
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Fetches all orders for a given user with their notifications eagerly loaded
     * in one SQL JOIN query (avoids N+1 problem).
     *
     * <p>Without JOIN FETCH Hibernate would first load all orders, then
     * issue a separate SELECT for each order's notifications collection.</p>
     *
     * @param userId the user identifier
     * @return list of orders with notifications pre-fetched
     */
    @Query("""
            SELECT DISTINCT o FROM Order o
            LEFT JOIN FETCH o.notifications n
            WHERE o.userId = :userId
            ORDER BY o.createdAt DESC
            """)
    List<Order> findAllByUserIdWithNotifications(@Param("userId") String userId);

    /**
     * Finds a single order with its notifications pre-loaded.
     *
     * @param orderId the order UUID
     * @return optional order with notifications
     */
    @Query("""
            SELECT o FROM Order o
            LEFT JOIN FETCH o.notifications n
            WHERE o.id = :orderId
            """)
    Optional<Order> findByIdWithNotifications(@Param("orderId") UUID orderId);
}
