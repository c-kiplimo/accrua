package com.accrua.repository

import com.accrua.domain.model.Meter
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for Meter entities.
 */
@Repository
interface MeterRepository : CoroutineCrudRepository<Meter, UUID> {

    /**
     * Find meters by tenant
     */
    fun findByTenantId(tenantId: String): Flow<Meter>

    /**
     * Find active meters by tenant
     */
    fun findByTenantIdAndActive(tenantId: String, active: Boolean = true): Flow<Meter>

    /**
     * Find meter by name and tenant
     */
    suspend fun findByTenantIdAndName(tenantId: String, name: String): Meter?

    /**
     * Check if meter exists
     */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM meters
            WHERE tenant_id = :tenantId AND name = :name
        )
    """)
    suspend fun existsByTenantIdAndName(tenantId: String, name: String): Boolean

    /**
     * Find meter by ID and tenant (tenant isolation)
     */
    suspend fun findByIdAndTenantId(id: UUID, tenantId: String): Meter?

    /**
     * Count active meters for tenant
     */
    suspend fun countByTenantIdAndActive(tenantId: String, active: Boolean = true): Long
}
