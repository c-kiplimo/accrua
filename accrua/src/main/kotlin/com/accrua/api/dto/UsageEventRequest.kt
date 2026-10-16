package com.accrua.api.dto

import com.accrua.domain.model.UsageEvent
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Request DTO for ingesting a single usage event
 */
data class UsageEventRequest(
    @field:NotNull(message = "allocation_id is required")
    val allocationId: UUID,

    @field:NotBlank(message = "end_user_id is required")
    val endUserId: String,

    @field:NotNull(message = "meter_id is required")
    val meterId: UUID,

    @field:NotNull(message = "amount is required")
    @field:Positive(message = "amount must be positive")
    val amount: BigDecimal,

    @field:NotNull(message = "occurred_at is required")
    val occurredAt: Instant,

    @field:NotBlank(message = "event_type is required")
    val eventType: String,

    val metadata: Map<String, Any> = emptyMap(),

    val idempotencyKey: String? = null
)

/**
 * Extension function to convert DTO to domain model
 */
fun UsageEventRequest.toUsageEvent(tenantId: String): UsageEvent {
    return UsageEvent.create(
        tenantId = tenantId,
        allocationId = this.allocationId,
        endUserId = this.endUserId,
        meterId = this.meterId,
        amount = this.amount,
        occurredAt = this.occurredAt,
        eventType = this.eventType,
        metadata = this.metadata,
        idempotencyKey = this.idempotencyKey
    )
}

/**
 * Request DTO for batch event ingestion
 */
data class UsageEventBatchRequest(
    val events: List<UsageEventRequest>
) {
    init {
        require(events.isNotEmpty()) { "Batch must contain at least one event" }
        require(events.size <= 1000) { "Batch size cannot exceed 1000 events" }
    }
}
