package com.accrua.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * LedgerEntry represents an immutable record in the financial ledger.
 *
 * Key principles:
 * - Immutability: Ledger entries are never updated, only appended
 * - Event sourcing: Complete audit trail of all financial transactions
 * - Multi-view: Same ledger, different perspectives (provider/sponsor/user)
 * - Double-entry: Every debit has a corresponding credit (future enhancement)
 */
@Table("ledger_entries")
data class LedgerEntry(
    @Id
    val id: UUID = UUID.randomUUID(),

    /**
     * Tenant isolation
     */
    val tenantId: String,

    /**
     * Allocation being affected
     */
    val allocationId: UUID,

    /**
     * Originating event (usage, decay, adjustment)
     */
    val eventId: UUID,

    /**
     * Type of ledger entry
     */
    val eventType: LedgerEventType,

    /**
     * Amount (positive for credits, negative for debits)
     */
    val amount: BigDecimal,

    /**
     * When this entry was created
     */
    val timestamp: Instant = Instant.now(),

    /**
     * Additional context
     */
    val metadata: Map<String, Any> = emptyMap(),

    /**
     * Reference to related entities
     */
    val sponsorId: String? = null,
    val endUserId: String? = null,
    val providerId: String? = null
) {
    val isDebit: Boolean
        get() = amount < BigDecimal.ZERO

    val isCredit: Boolean
        get() = amount > BigDecimal.ZERO

    companion object {
        /**
         * Create a usage entry (debit)
         */
        fun forUsage(
            tenantId: String,
            allocationId: UUID,
            eventId: UUID,
            amount: BigDecimal,
            sponsorId: String,
            endUserId: String,
            metadata: Map<String, Any> = emptyMap()
        ): LedgerEntry = LedgerEntry(
            tenantId = tenantId,
            allocationId = allocationId,
            eventId = eventId,
            eventType = LedgerEventType.USAGE,
            amount = -amount.abs(), // Debit (negative)
            sponsorId = sponsorId,
            endUserId = endUserId,
            metadata = metadata
        )

        /**
         * Create an allocation entry (credit)
         */
        fun forAllocation(
            tenantId: String,
            allocationId: UUID,
            amount: BigDecimal,
            sponsorId: String,
            providerId: String,
            metadata: Map<String, Any> = emptyMap()
        ): LedgerEntry = LedgerEntry(
            tenantId = tenantId,
            allocationId = allocationId,
            eventId = allocationId, // Use allocation ID as event ID
            eventType = LedgerEventType.ALLOCATION,
            amount = amount.abs(), // Credit (positive)
            sponsorId = sponsorId,
            providerId = providerId,
            metadata = metadata
        )

        /**
         * Create a decay entry (debit)
         */
        fun forDecay(
            tenantId: String,
            allocationId: UUID,
            eventId: UUID,
            amount: BigDecimal,
            sponsorId: String,
            metadata: Map<String, Any> = emptyMap()
        ): LedgerEntry = LedgerEntry(
            tenantId = tenantId,
            allocationId = allocationId,
            eventId = eventId,
            eventType = LedgerEventType.DECAY,
            amount = -amount.abs(), // Debit (negative)
            sponsorId = sponsorId,
            metadata = metadata
        )

        /**
         * Create an adjustment entry (can be credit or debit)
         */
        fun forAdjustment(
            tenantId: String,
            allocationId: UUID,
            eventId: UUID,
            amount: BigDecimal,
            reason: String,
            metadata: Map<String, Any> = emptyMap()
        ): LedgerEntry = LedgerEntry(
            tenantId = tenantId,
            allocationId = allocationId,
            eventId = eventId,
            eventType = LedgerEventType.ADJUSTMENT,
            amount = amount,
            metadata = metadata + ("reason" to reason)
        )
    }
}

/**
 * Types of ledger events
 */
enum class LedgerEventType {
    /**
     * Initial allocation (credit)
     */
    ALLOCATION,

    /**
     * Resource consumption (debit)
     */
    USAGE,

    /**
     * Time-based decay (debit)
     */
    DECAY,

    /**
     * Expiration (debit)
     */
    EXPIRATION,

    /**
     * Manual adjustment (credit or debit)
     */
    ADJUSTMENT,

    /**
     * Refund (credit)
     */
    REFUND,

    /**
     * Charge (for invoicing)
     */
    CHARGE
}

/**
 * Ledger summary for a time period
 */
data class LedgerSummary(
    val allocationId: UUID,
    val startDate: Instant,
    val endDate: Instant,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val totalCredits: BigDecimal,
    val totalDebits: BigDecimal,
    val netChange: BigDecimal,
    val entryCount: Int,
    val byEventType: Map<LedgerEventType, BigDecimal>
)
