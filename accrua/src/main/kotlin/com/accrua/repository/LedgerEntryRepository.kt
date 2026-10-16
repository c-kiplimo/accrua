package com.accrua.repository

import com.accrua.domain.model.LedgerEntry
import com.accrua.domain.model.LedgerEventType
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

/**
 * Repository for LedgerEntry (immutable audit log).
 */
@Repository
interface LedgerEntryRepository : CoroutineCrudRepository<LedgerEntry, UUID> {

    /**
     * Find entries by tenant
     */
    fun findByTenantIdOrderByTimestampDesc(tenantId: String): Flow<LedgerEntry>

    /**
     * Find entries by allocation
     */
    fun findByAllocationIdOrderByTimestampDesc(allocationId: UUID): Flow<LedgerEntry>

    /**
     * Find entries by sponsor
     */
    @Query("""
        SELECT * FROM ledger_entries
        WHERE sponsor_id = :sponsorId
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    fun findBySponsorId(sponsorId: String, limit: Int = 1000): Flow<LedgerEntry>

    /**
     * Find entries in time range
     */
    @Query("""
        SELECT * FROM ledger_entries
        WHERE allocation_id = :allocationId
          AND timestamp BETWEEN :startTime AND :endTime
        ORDER BY timestamp DESC
    """)
    fun findByAllocationIdAndTimeRange(
        allocationId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Flow<LedgerEntry>

    /**
     * Find entries by event type
     */
    fun findByEventTypeOrderByTimestampDesc(eventType: LedgerEventType): Flow<LedgerEntry>

    /**
     * Calculate total credits for an allocation
     */
    @Query("""
        SELECT COALESCE(SUM(amount), 0) FROM ledger_entries
        WHERE allocation_id = :allocationId
          AND amount > 0
    """)
    suspend fun sumCredits(allocationId: UUID): java.math.BigDecimal

    /**
     * Calculate total debits for an allocation
     */
    @Query("""
        SELECT COALESCE(SUM(ABS(amount)), 0) FROM ledger_entries
        WHERE allocation_id = :allocationId
          AND amount < 0
    """)
    suspend fun sumDebits(allocationId: UUID): java.math.BigDecimal

    /**
     * Find unbilled entries for a sponsor
     */
    @Query("""
        SELECT le.* FROM ledger_entries le
        WHERE le.sponsor_id = :sponsorId
          AND le.event_type = 'USAGE'
          AND le.timestamp BETWEEN :startTime AND :endTime
          AND NOT EXISTS (
            SELECT 1 FROM ledger_entries charge
            WHERE charge.event_id = le.id
              AND charge.event_type = 'CHARGE'
          )
        ORDER BY le.timestamp DESC
    """)
    fun findUnbilledUsage(
        sponsorId: String,
        startTime: Instant,
        endTime: Instant
    ): Flow<LedgerEntry>

    /**
     * Sum amount by event type for an allocation in a time range
     */
    @Query("""
        SELECT event_type, COALESCE(SUM(amount), 0) as total
        FROM ledger_entries
        WHERE allocation_id = :allocationId
          AND timestamp BETWEEN :startTime AND :endTime
        GROUP BY event_type
    """)
    suspend fun sumByEventType(
        allocationId: UUID,
        startTime: Instant,
        endTime: Instant
    ): Map<LedgerEventType, java.math.BigDecimal>
}
