package com.accrua.repository

import com.accrua.domain.model.BalanceSnapshot
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

/**
 * Repository for BalanceSnapshot (materialized view for fast queries).
 */
@Repository
interface BalanceSnapshotRepository : CoroutineCrudRepository<BalanceSnapshot, UUID> {

    /**
     * Find latest snapshot for an allocation
     */
    @Query("""
        SELECT * FROM balance_snapshots
        WHERE allocation_id = :allocationId
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun findLatestByAllocationId(allocationId: UUID): BalanceSnapshot?

    /**
     * Find snapshots for an allocation
     */
    fun findByAllocationIdOrderByTimestampDesc(allocationId: UUID): Flow<BalanceSnapshot>

    /**
     * Find snapshot at specific time
     */
    @Query("""
        SELECT * FROM balance_snapshots
        WHERE allocation_id = :allocationId
          AND timestamp <= :timestamp
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    suspend fun findByAllocationIdAtTime(
        allocationId: UUID,
        timestamp: Instant
    ): BalanceSnapshot?

    /**
     * Find snapshots in time range
     */
    @Query("""
        SELECT * FROM balance_snapshots
        WHERE allocation_id = :allocationId
          AND timestamp BETWEEN :startTime AND :endTime
        ORDER BY timestamp DESC
    """)
    fun findByAllocationIdAndTimeRange(
        allocationId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Flow<BalanceSnapshot>

    /**
     * Delete old snapshots (cleanup)
     */
    @Query("""
        DELETE FROM balance_snapshots
        WHERE timestamp < :before
    """)
    suspend fun deleteOlderThan(before: Instant): Long
}
