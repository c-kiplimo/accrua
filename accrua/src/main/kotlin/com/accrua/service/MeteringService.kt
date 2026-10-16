package com.accrua.service

import com.accrua.domain.model.*
import com.accrua.repository.AllocationRepository
import com.accrua.repository.MeterRepository
import com.accrua.repository.UsageEventRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * MeteringService handles event ingestion and validation.
 *
 * Responsibilities:
 * - Validate incoming usage events
 * - Check idempotency (no double-counting)
 * - Persist events to database
 * - Publish to Kafka for async processing
 * - Return receipt to client
 *
 * Design patterns:
 * - Command pattern (event ingestion as command)
 * - Repository pattern (data access)
 * - Publisher pattern (Kafka events)
 */
@Service
class MeteringService(
    private val usageEventRepository: UsageEventRepository,
    private val allocationRepository: AllocationRepository,
    private val meterRepository: MeterRepository,
    private val idempotencyService: IdempotencyService,
    private val kafkaTemplate: KafkaTemplate<String, UsageEvent>
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val USAGE_EVENTS_TOPIC = "usage-events"
        private const val MAX_EVENT_AGE_SECONDS = 86400L // 24 hours
    }

    /**
     * Ingest a single usage event
     */
    @Transactional
    suspend fun ingest(event: UsageEvent, tenantId: String): EventReceipt {
        logger.info("Ingesting usage event: ${event.id} for tenant: $tenantId")

        // 1. Tenant validation
        if (event.tenantId != tenantId) {
            logger.warn("Tenant mismatch: event=${event.tenantId}, context=$tenantId")
            return EventReceipt(
                eventId = event.id,
                status = EventStatus.REJECTED,
                message = "Tenant mismatch"
            )
        }

        // 2. Idempotency check
        if (idempotencyService.isDuplicate(event.idempotencyKey)) {
            logger.debug("Duplicate event detected: ${event.idempotencyKey}")

            val existingReceipt = idempotencyService.getReceipt(event.idempotencyKey)
            return existingReceipt ?: EventReceipt(
                eventId = event.id,
                status = EventStatus.DUPLICATE,
                message = "Duplicate event"
            )
        }

        // 3. Validate event
        try {
            event.validate()
        } catch (e: IllegalArgumentException) {
            logger.error("Event validation failed: ${e.message}", e)
            return EventReceipt(
                eventId = event.id,
                status = EventStatus.REJECTED,
                message = "Validation failed: ${e.message}"
            )
        }

        // 4. Validate references (allocation and meter exist)
        val validationResult = validateReferences(event, tenantId)
        if (validationResult != null) {
            return validationResult
        }

        // 5. Check event age (prevent stale events)
        if (event.getAge() > MAX_EVENT_AGE_SECONDS) {
            logger.warn("Event too old: ${event.id}, age=${event.getAge()}s")
            return EventReceipt(
                eventId = event.id,
                status = EventStatus.REJECTED,
                message = "Event too old (max 24 hours)"
            )
        }

        // 6. Persist event to database
        try {
            usageEventRepository.save(event)
            logger.debug("Event persisted: ${event.id}")
        } catch (e: Exception) {
            logger.error("Failed to persist event: ${event.id}", e)
            return EventReceipt(
                eventId = event.id,
                status = EventStatus.FAILED,
                message = "Database error: ${e.message}"
            )
        }

        // 7. Publish to Kafka for async processing
        val receipt = try {
            publishToKafka(event)

            EventReceipt(
                eventId = event.id,
                status = EventStatus.ACCEPTED,
                message = "Event accepted for processing"
            )
        } catch (e: Exception) {
            logger.error("Failed to publish event to Kafka: ${event.id}", e)

            EventReceipt(
                eventId = event.id,
                status = EventStatus.ACCEPTED,
                message = "Event persisted but async processing delayed"
            )
        }

        // 8. Store receipt for idempotency
        idempotencyService.storeReceipt(event.idempotencyKey, receipt)

        logger.info("Event ingestion complete: ${event.id}, status=${receipt.status}")
        return receipt
    }

    /**
     * Ingest a batch of events
     */
    @Transactional
    suspend fun ingestBatch(events: List<UsageEvent>, tenantId: String): BatchReceipt {
        logger.info("Ingesting batch of ${events.size} events for tenant: $tenantId")

        val receipts = events.map { event ->
            ingest(event, tenantId)
        }

        val statusCounts = receipts.groupingBy { it.status }.eachCount()

        return BatchReceipt(
            total = events.size,
            accepted = statusCounts[EventStatus.ACCEPTED] ?: 0,
            duplicate = statusCounts[EventStatus.DUPLICATE] ?: 0,
            rejected = statusCounts[EventStatus.REJECTED] ?: 0,
            receipts = receipts
        )
    }

    /**
     * Query usage events for an allocation
     */
    suspend fun getUsageHistory(
        allocationId: UUID,
        tenantId: String,
        startTime: Instant? = null,
        endTime: Instant? = null,
        limit: Int = 100
    ): List<UsageEvent> {
        logger.debug("Querying usage history: allocation=$allocationId, tenant=$tenantId")

        // Validate allocation belongs to tenant
        val allocation = allocationRepository.findByIdAndTenantId(allocationId, tenantId)
            ?: throw IllegalArgumentException("Allocation not found or unauthorized")

        return if (startTime != null && endTime != null) {
            usageEventRepository.findByAllocationIdAndTimeRange(
                allocationId = allocationId,
                startTime = startTime,
                endTime = endTime,
                limit = limit
            ).toList()
        } else {
            usageEventRepository.findByAllocationId(allocationId)
                .toList()
                .take(limit)
        }
    }

    /**
     * Get usage summary for an allocation
     */
    suspend fun getUsageSummary(
        allocationId: UUID,
        tenantId: String,
        startTime: Instant,
        endTime: Instant
    ): UsageSummary {
        logger.debug("Calculating usage summary: allocation=$allocationId")

        // Validate allocation
        val allocation = allocationRepository.findByIdAndTenantId(allocationId, tenantId)
            ?: throw IllegalArgumentException("Allocation not found or unauthorized")

        val totalAmount = usageEventRepository.sumAmountByAllocationIdAndTimeRange(
            allocationId = allocationId,
            startTime = startTime,
            endTime = endTime
        )

        val eventCount = usageEventRepository.countByAllocationIdAndTimeRange(
            allocationId = allocationId,
            startTime = startTime,
            endTime = endTime
        )

        return UsageSummary(
            allocationId = allocationId,
            startTime = startTime,
            endTime = endTime,
            totalAmount = totalAmount,
            eventCount = eventCount,
            averageAmount = if (eventCount > 0) {
                totalAmount.divide(BigDecimal(eventCount), 4, java.math.RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
        )
    }

    /**
     * Validate event references (allocation and meter)
     */
    private suspend fun validateReferences(event: UsageEvent, tenantId: String): EventReceipt? {
        // Check allocation exists and is active
        val allocation = allocationRepository.findByIdAndTenantId(event.allocationId, tenantId)

        if (allocation == null) {
            logger.warn("Allocation not found: ${event.allocationId}")
            return EventReceipt(
                eventId = event.id,
                status = EventStatus.REJECTED,
                message = "Allocation not found"
            )
        }

        if (!allocation.canAcceptUsage(event.amount)) {
            logger.warn("Allocation cannot accept usage: ${event.allocationId}, status=${allocation.status}")
            return EventReceipt(
                eventId = event.id,
                status = EventStatus.REJECTED,
                message = "Allocation inactive or insufficient balance"
            )
        }

        // Check meter exists
        val meter = meterRepository.findByIdAndTenantId(event.meterId, tenantId)

        if (meter == null) {
            logger.warn("Meter not found: ${event.meterId}")
            return EventReceipt(
                eventId = event.id,
                status = EventStatus.REJECTED,
                message = "Meter not found"
            )
        }

        if (!meter.active) {
            logger.warn("Meter inactive: ${event.meterId}")
            return EventReceipt(
                eventId = event.id,
                status = EventStatus.REJECTED,
                message = "Meter inactive"
            )
        }

        return null // Validation passed
    }

    /**
     * Publish event to Kafka
     */
    private suspend fun publishToKafka(event: UsageEvent) {
        logger.debug("Publishing event to Kafka: ${event.id}")

        try {
            // Use tenant ID as partition key for tenant isolation
            kafkaTemplate.send(
                USAGE_EVENTS_TOPIC,
                event.tenantId,
                event
            ).get() // Wait for acknowledgment (exactly-once)

            logger.debug("Event published to Kafka: ${event.id}")
        } catch (e: Exception) {
            logger.error("Failed to publish to Kafka: ${event.id}", e)
            throw e
        }
    }
}

/**
 * Usage summary DTO
 */
data class UsageSummary(
    val allocationId: UUID,
    val startTime: Instant,
    val endTime: Instant,
    val totalAmount: BigDecimal,
    val eventCount: Long,
    val averageAmount: BigDecimal
)
