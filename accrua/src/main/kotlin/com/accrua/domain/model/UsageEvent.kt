package com.accrua.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * UsageEvent represents a discrete consumption event.
 *
 * Key domain concepts:
 * - Immutability: Events are immutable (event sourcing pattern)
 * - Idempotency: Each event has a unique ID for deduplication
 * - Temporal accuracy: Separates occurrence time from processing time
 * - Tenant isolation: Multi-tenant by design
 */
@Table("usage_events")
data class UsageEvent(
    @Id
    val id: UUID = UUID.randomUUID(),

    /**
     * Tenant identifier for multi-tenancy isolation
     */
    val tenantId: String,

    /**
     * The allocation being consumed from
     */
    val allocationId: UUID,

    /**
     * The end user performing the consumption
     */
    val endUserId: String,

    /**
     * The meter this event belongs to
     */
    val meterId: UUID,

    /**
     * Amount consumed in this event
     */
    val amount: BigDecimal,

    /**
     * When the event was created/received
     */
    val timestamp: Instant = Instant.now(),

    /**
     * When the consumption actually occurred (may differ from processing time)
     * This is important for accurate billing and analytics
     */
    val occurredAt: Instant,

    /**
     * Type of event for categorization
     * Examples: "api_call", "data_transfer", "compute_usage"
     */
    val eventType: String,

    /**
     * Additional event metadata (stored as JSONB)
     */
    val metadata: Map<String, Any> = emptyMap(),

    /**
     * Idempotency key for deduplication (client-provided or auto-generated)
     */
    val idempotencyKey: String = id.toString(),

    /**
     * When this event was processed (null until processed)
     */
    val processedAt: Instant? = null
) {
    /**
     * Check if event has been processed
     */
    val isProcessed: Boolean
        get() = processedAt != null

    /**
     * Get event age (time since occurrence)
     */
    fun getAge(now: Instant = Instant.now()): Long =
        now.epochSecond - occurredAt.epochSecond

    /**
     * Mark event as processed
     */
    fun markProcessed(now: Instant = Instant.now()): UsageEvent =
        copy(processedAt = now)

    /**
     * Validate event
     */
    fun validate() {
        require(tenantId.isNotBlank()) { "Tenant ID cannot be blank" }
        require(endUserId.isNotBlank()) { "End user ID cannot be blank" }
        require(amount > BigDecimal.ZERO) { "Amount must be positive" }
        require(eventType.isNotBlank()) { "Event type cannot be blank" }
        require(occurredAt.isBefore(Instant.now().plusSeconds(60))) {
            "Occurred at cannot be in the future"
        }
        require(idempotencyKey.isNotBlank()) { "Idempotency key cannot be blank" }
    }

    companion object {
        /**
         * Create a new usage event
         */
        fun create(
            tenantId: String,
            allocationId: UUID,
            endUserId: String,
            meterId: UUID,
            amount: BigDecimal,
            occurredAt: Instant,
            eventType: String,
            metadata: Map<String, Any> = emptyMap(),
            idempotencyKey: String? = null
        ): UsageEvent {
            val event = UsageEvent(
                tenantId = tenantId,
                allocationId = allocationId,
                endUserId = endUserId,
                meterId = meterId,
                amount = amount,
                occurredAt = occurredAt,
                eventType = eventType,
                metadata = metadata,
                idempotencyKey = idempotencyKey ?: UUID.randomUUID().toString()
            )

            event.validate()
            return event
        }

        /**
         * Create a batch of events (for batch ingestion)
         */
        fun createBatch(
            tenantId: String,
            allocationId: UUID,
            endUserId: String,
            meterId: UUID,
            events: List<Pair<BigDecimal, Instant>>,
            eventType: String
        ): List<UsageEvent> =
            events.map { (amount, occurredAt) ->
                create(
                    tenantId = tenantId,
                    allocationId = allocationId,
                    endUserId = endUserId,
                    meterId = meterId,
                    amount = amount,
                    occurredAt = occurredAt,
                    eventType = eventType
                )
            }
    }
}

/**
 * Event receipt returned after successful ingestion
 */
data class EventReceipt(
    val eventId: UUID,
    val status: EventStatus,
    val timestamp: Instant = Instant.now(),
    val message: String? = null
)

/**
 * Event processing status
 */
enum class EventStatus {
    ACCEPTED,
    DUPLICATE,
    REJECTED,
    PROCESSING,
    PROCESSED,
    FAILED
}

/**
 * Batch receipt for multiple events
 */
data class BatchReceipt(
    val total: Int,
    val accepted: Int,
    val duplicate: Int,
    val rejected: Int,
    val receipts: List<EventReceipt>,
    val timestamp: Instant = Instant.now()
)
