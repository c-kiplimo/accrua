package com.accrua.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * BalanceSnapshot is a materialized view for fast balance queries.
 *
 * This is part of the CQRS pattern:
 * - Write model: UsageEvents + Allocations (event sourced)
 * - Read model: BalanceSnapshots (materialized view)
 *
 * Benefits:
 * - O(1) balance queries (no need to sum events)
 * - Optimistic locking prevents concurrent update conflicts
 * - Temporal queries (balance at any point in time)
 */
@Table("balance_snapshots")
data class BalanceSnapshot(
    @Id
    val id: UUID = UUID.randomUUID(),

    /**
     * Reference to allocation
     */
    val allocationId: UUID,

    /**
     * Current balance (total - used)
     */
    val balance: BigDecimal,

    /**
     * Snapshot timestamp
     */
    val timestamp: Instant = Instant.now(),

    /**
     * Optimistic locking version
     */
    @Version
    val version: Long = 0
) {
    fun update(newBalance: BigDecimal): BalanceSnapshot = copy(
        balance = newBalance,
        timestamp = Instant.now(),
        version = version + 1
    )

    fun applyDelta(delta: BigDecimal): BalanceSnapshot = update(balance + delta)

    companion object {
        fun fromAllocation(allocation: Allocation): BalanceSnapshot =
            BalanceSnapshot(
                allocationId = allocation.id,
                balance = allocation.remainingAmount,
                timestamp = allocation.lastActivityAt
            )
    }
}

/**
 * Balance view for different stakeholders
 */
sealed class BalanceView {

    /**
     * Provider view: Revenue tracking
     */
    data class Provider(
        val totalRevenue: BigDecimal,
        val revenueByPeriod: Map<String, BigDecimal>,
        val breakageRevenue: BigDecimal,
        val activeAllocations: BigDecimal,
        val pendingSettlement: BigDecimal,
        val timestamp: Instant = Instant.now()
    ) : BalanceView()

    /**
     * Sponsor view: Spend tracking
     */
    data class Sponsor(
        val totalSpend: BigDecimal,
        val spendByPeriod: Map<String, BigDecimal>,
        val activeBalance: BigDecimal,
        val utilizationRate: Double,
        val topConsumers: List<EndUserConsumption>,
        val projectedDepletion: Instant?,
        val timestamp: Instant = Instant.now()
    ) : BalanceView()

    /**
     * End user view: Usage tracking
     */
    data class EndUser(
        val availableBalance: BigDecimal,
        val usedThisPeriod: BigDecimal,
        val remainingQuota: BigDecimal,
        val usageHistory: List<UsageRecord>,
        val projectedDepletion: Instant?,
        val timestamp: Instant = Instant.now()
    ) : BalanceView()
}

/**
 * End user consumption summary
 */
data class EndUserConsumption(
    val userId: String,
    val totalUsage: BigDecimal,
    val percentage: Double,
    val lastActivityAt: Instant
)

/**
 * Usage record for history
 */
data class UsageRecord(
    val eventId: UUID,
    val amount: BigDecimal,
    val timestamp: Instant,
    val eventType: String,
    val metadata: Map<String, Any>
)
