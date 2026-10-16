package com.accrua.repository

import com.accrua.domain.model.UsageEvent
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

/**
 * Repository for UsageEvent entities.
 */
@Repository
interface UsageEventRepository : CoroutineCrudRepository<UsageEvent, UUID> {

    /**
     * Find events by allocation
     */
    fun findByAllocationId(allocationId: UUID): Flow<UsageEvent>

    /**
     * Find events by tenant
     */
    fun findByTenantId(tenantId: String): Flow<UsageEvent>

    /**
     * Find events by idempotency key (for deduplication)
     */
    suspend fun findByIdempotencyKey(idempotencyKey: String): UsageEvent?

    /**
     * Find events in time range
     */
    @Query("""
        SELECT * FROM usage_events
        WHERE allocation_id = :allocationId
          AND occurred_at BETWEEN :startTime AND :endTime
        ORDER BY occurred_at DESC
        LIMIT :limit
    """)
    fun findByAllocationIdAndTimeRange(
        allocationId: UUID,
        startTime: Instant,
        endTime: Instant,
        limit: Int = 100
    ): Flow<UsageEvent>

    /**
     * Find unprocessed events
     */
    @Query("""
        SELECT * FROM usage_events
        WHERE processed_at IS NULL
        ORDER BY timestamp ASC
        LIMIT :limit
    """)
    fun findUnprocessed(limit: Int = 1000): Flow<UsageEvent>

    /**
     * Count events by allocation in time range
     */
    @Query("""
        SELECT COUNT(*) FROM usage_events
        WHERE allocation_id = :allocationId
          AND occurred_at BETWEEN :startTime AND :endTime
    """)
    suspend fun countByAllocationIdAndTimeRange(
        allocationId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Long

    /**
     * Sum usage by allocation in time range
     */
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM usage_events
        WHERE allocation_id = :allocationId
          AND occurred_at BETWEEN :startTime AND :endTime
    """)
    suspend fun sumAmountByAllocationIdAndTimeRange(
        allocationId: UUID,
        startTime: Instant,
        endTime: Instant
    ): java.math.BigDecimal

    /**
     * Find events by end user
     */
    @Query("""
        SELECT * FROM usage_events
        WHERE end_user_id = :endUserId
          AND tenant_id = :tenantId
        ORDER BY occurred_at DESC
        LIMIT :limit
    """)
    fun findByEndUserIdAndTenantId(
        endUserId: String,
        tenantId: String,
        limit: Int = 100
    ): Flow<UsageEvent>
}
