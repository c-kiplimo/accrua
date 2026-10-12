package com.accrua.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.*

/**
 * Allocation represents a pool of resources granted to a sponsor organization.
 *
 * Key domain concepts:
 * - Immutability: All monetary amounts are immutable BigDecimal for precision
 * - Time-awareness: Tracks creation, expiration, and last activity
 * - Status lifecycle: ACTIVE -> DEPLETED/EXPIRED/SUSPENDED/CANCELLED
 * - Decay support: Optional decay policy for time-based resource reduction
 */
@Table("allocations")
data class Allocation(
    @Id
    val id: UUID = UUID.randomUUID(),

    /**
     * Tenant identifier for multi-tenancy isolation
     */
    val tenantId: String,

    /**
     * The organization receiving the allocation
     */
    val sponsorId: String,

    /**
     * The entity providing the resources
     */
    val providerId: String,

    /**
     * Reference to the meter defining what is being measured
     */
    val meterId: UUID,

    /**
     * Total amount allocated (immutable after creation)
     */
    val totalAmount: BigDecimal,

    /**
     * Amount consumed so far
     */
    val usedAmount: BigDecimal = BigDecimal.ZERO,

    /**
     * Current status of the allocation
     */
    val status: AllocationStatus = AllocationStatus.ACTIVE,

    /**
     * When the allocation was created
     */
    val createdAt: Instant = Instant.now(),

    /**
     * When the allocation expires (null = no expiration)
     */
    val expiresAt: Instant? = null,

    /**
     * Last time there was activity (usage or decay)
     */
    val lastActivityAt: Instant = Instant.now(),

    /**
     * Name of the decay policy to apply (null = no decay)
     * Examples: "linear_1pct_daily", "expiration_30d"
     */
    val decayPolicy: String? = null,

    /**
     * Additional metadata stored as JSON
     */
    val metadata: Map<String, Any> = emptyMap(),

    /**
     * Version for optimistic locking
     */
    val version: Long = 0
) {
    /**
     * Remaining balance
     */
    val remainingAmount: BigDecimal
        get() = (totalAmount - usedAmount).max(BigDecimal.ZERO)

    /**
     * Utilization rate (0.0 to 1.0)
     */
    val utilizationRate: Double
        get() = if (totalAmount > BigDecimal.ZERO) {
            usedAmount.divide(totalAmount, 4, RoundingMode.HALF_UP).toDouble()
        } else 0.0

    /**
     * Check if allocation is expired
     */
    fun isExpired(now: Instant = Instant.now()): Boolean =
        expiresAt?.let { now >= it } ?: false

    /**
     * Check if allocation is depleted (fully used)
     */
    fun isDepleted(): Boolean =
        usedAmount >= totalAmount

    /**
     * Check if allocation can accept usage
     */
    fun canAcceptUsage(amount: BigDecimal, now: Instant = Instant.now()): Boolean =
        status == AllocationStatus.ACTIVE &&
        !isExpired(now) &&
        remainingAmount >= amount

    /**
     * Apply usage to the allocation (returns new immutable instance)
     */
    fun applyUsage(amount: BigDecimal, now: Instant = Instant.now()): Allocation {
        require(amount >= BigDecimal.ZERO) { "Usage amount must be non-negative" }
        require(canAcceptUsage(amount, now)) { "Cannot accept usage: insufficient balance or inactive allocation" }

        val newUsedAmount = usedAmount + amount
        val newStatus = if (newUsedAmount >= totalAmount) {
            AllocationStatus.DEPLETED
        } else {
            status
        }

        return copy(
            usedAmount = newUsedAmount,
            status = newStatus,
            lastActivityAt = now,
            version = version + 1
        )
    }

    /**
     * Apply decay to the allocation (reduces remaining balance)
     */
    fun applyDecay(amount: BigDecimal, now: Instant = Instant.now()): Allocation {
        require(amount >= BigDecimal.ZERO) { "Decay amount must be non-negative" }

        // Decay reduces the total amount, effectively reducing remaining balance
        val newTotalAmount = (totalAmount - amount).max(usedAmount)
        val newStatus = if (newTotalAmount <= usedAmount) {
            AllocationStatus.DEPLETED
        } else if (amount >= remainingAmount) {
            AllocationStatus.EXPIRED
        } else {
            status
        }

        return copy(
            totalAmount = newTotalAmount,
            status = newStatus,
            lastActivityAt = now,
            version = version + 1
        )
    }

    /**
     * Mark allocation as suspended
     */
    fun suspend(now: Instant = Instant.now()): Allocation =
        copy(
            status = AllocationStatus.SUSPENDED,
            lastActivityAt = now,
            version = version + 1
        )

    /**
     * Mark allocation as cancelled
     */
    fun cancel(now: Instant = Instant.now()): Allocation =
        copy(
            status = AllocationStatus.CANCELLED,
            lastActivityAt = now,
            version = version + 1
        )

    /**
     * Reactivate a suspended allocation
     */
    fun reactivate(now: Instant = Instant.now()): Allocation {
        require(status == AllocationStatus.SUSPENDED) { "Can only reactivate suspended allocations" }

        return copy(
            status = AllocationStatus.ACTIVE,
            lastActivityAt = now,
            version = version + 1
        )
    }

    companion object {
        /**
         * Create a new allocation
         */
        fun create(
            tenantId: String,
            sponsorId: String,
            providerId: String,
            meterId: UUID,
            totalAmount: BigDecimal,
            expiresAt: Instant? = null,
            decayPolicy: String? = null,
            metadata: Map<String, Any> = emptyMap()
        ): Allocation {
            require(tenantId.isNotBlank()) { "Tenant ID cannot be blank" }
            require(sponsorId.isNotBlank()) { "Sponsor ID cannot be blank" }
            require(providerId.isNotBlank()) { "Provider ID cannot be blank" }
            require(totalAmount > BigDecimal.ZERO) { "Total amount must be positive" }

            val now = Instant.now()
            expiresAt?.let {
                require(it.isAfter(now)) { "Expiration date must be in the future" }
            }

            return Allocation(
                tenantId = tenantId,
                sponsorId = sponsorId,
                providerId = providerId,
                meterId = meterId,
                totalAmount = totalAmount,
                expiresAt = expiresAt,
                decayPolicy = decayPolicy,
                metadata = metadata,
                createdAt = now,
                lastActivityAt = now
            )
        }
    }
}

/**
 * Allocation lifecycle status
 */
enum class AllocationStatus {
    /**
     * Active and accepting usage
     */
    ACTIVE,

    /**
     * Fully consumed (used_amount >= total_amount)
     */
    DEPLETED,

    /**
     * Expired due to time-based expiration
     */
    EXPIRED,

    /**
     * Temporarily suspended (can be reactivated)
     */
    SUSPENDED,

    /**
     * Permanently cancelled
     */
    CANCELLED
}
