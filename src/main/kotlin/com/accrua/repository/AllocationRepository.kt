package com.accrua.repository

import com.accrua.domain.model.Allocation
import com.accrua.domain.model.AllocationStatus
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

/**
 * Repository for Allocation entities.
 *
 * Uses Spring Data R2DBC for reactive database access.
 * All methods return suspend functions or Flow for non-blocking operations.
 */
@Repository
interface AllocationRepository : CoroutineCrudRepository<Allocation, UUID> {

    /**
     * Find all allocations for a tenant
     */
    fun findByTenantId(tenantId: String): Flow<Allocation>

    /**
     * Find all allocations for a sponsor
     */
    fun findBySponsorId(sponsorId: String): Flow<Allocation>

    /**
     * Find active allocations
     */
    fun findByStatus(status: AllocationStatus): Flow<Allocation>

    /**
     * Find allocations that can accept usage
     */
    @Query("""
        SELECT * FROM allocations
        WHERE tenant_id = :tenantId
          AND status = 'ACTIVE'
          AND (expires_at IS NULL OR expires_at > :now)
          AND used_amount < total_amount
    """)
    fun findActiveForTenant(tenantId: String, now: Instant = Instant.now()): Flow<Allocation>

    /**
     * Find allocations with decay policy that are due for processing
     */
    @Query("""
        SELECT * FROM allocations
        WHERE status = 'ACTIVE'
          AND decay_policy IS NOT NULL
          AND last_activity_at < :threshold
    """)
    fun findActiveWithDecay(threshold: Instant): Flow<Allocation>

    /**
     * Find expired allocations
     */
    @Query("""
        SELECT * FROM allocations
        WHERE status = 'ACTIVE'
          AND expires_at IS NOT NULL
          AND expires_at <= :now
    """)
    fun findExpired(now: Instant = Instant.now()): Flow<Allocation>

    /**
     * Find allocations by sponsor and status
     */
    fun findBySponsorIdAndStatus(sponsorId: String, status: AllocationStatus): Flow<Allocation>

    /**
     * Find allocation by ID and tenant (for tenant isolation)
     */
    suspend fun findByIdAndTenantId(id: UUID, tenantId: String): Allocation?

    /**
     * Count active allocations for a sponsor
     */
    suspend fun countBySponsorIdAndStatus(sponsorId: String, status: AllocationStatus): Long
}
