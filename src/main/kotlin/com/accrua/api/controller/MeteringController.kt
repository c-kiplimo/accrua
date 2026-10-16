package com.accrua.api.controller

import com.accrua.api.dto.UsageEventRequest
import com.accrua.api.dto.UsageEventBatchRequest
import com.accrua.api.dto.toUsageEvent
import com.accrua.domain.model.BatchReceipt
import com.accrua.domain.model.EventReceipt
import com.accrua.service.MeteringService
import com.accrua.service.UsageSummary
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.*
import jakarta.validation.Valid

/**
 * REST API for event ingestion and metering.
 *
 * Endpoints:
 * - POST /api/v1/events - Ingest single event
 * - POST /api/v1/events/batch - Ingest batch of events
 * - GET /api/v1/allocations/{id}/usage - Get usage history
 * - GET /api/v1/allocations/{id}/summary - Get usage summary
 */
@RestController
@RequestMapping("/api/v1")
class MeteringController(
    private val meteringService: MeteringService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Ingest a single usage event
     */
    @PostMapping("/events")
    suspend fun ingestEvent(
        @Valid @RequestBody request: UsageEventRequest,
        authentication: Authentication
    ): ResponseEntity<EventReceipt> {
        val tenantId = extractTenantId(authentication)

        logger.info("Ingesting event for tenant: $tenantId")

        val event = request.toUsageEvent(tenantId)
        val receipt = meteringService.ingest(event, tenantId)

        val statusCode = when {
            receipt.status.name.contains("ACCEPTED") -> HttpStatus.OK
            receipt.status.name.contains("DUPLICATE") -> HttpStatus.OK
            receipt.status.name.contains("REJECTED") -> HttpStatus.BAD_REQUEST
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }

        return ResponseEntity.status(statusCode).body(receipt)
    }

    /**
     * Ingest a batch of events
     */
    @PostMapping("/events/batch")
    suspend fun ingestBatch(
        @Valid @RequestBody request: UsageEventBatchRequest,
        authentication: Authentication
    ): ResponseEntity<BatchReceipt> {
        val tenantId = extractTenantId(authentication)

        logger.info("Ingesting batch of ${request.events.size} events for tenant: $tenantId")

        val events = request.events.map { it.toUsageEvent(tenantId) }
        val receipt = meteringService.ingestBatch(events, tenantId)

        return ResponseEntity.ok(receipt)
    }

    /**
     * Get usage history for an allocation
     */
    @GetMapping("/allocations/{allocationId}/usage")
    suspend fun getUsageHistory(
        @PathVariable allocationId: UUID,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: Instant?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: Instant?,
        @RequestParam(defaultValue = "100") limit: Int,
        authentication: Authentication
    ): ResponseEntity<UsageHistoryResponse> {
        val tenantId = extractTenantId(authentication)

        logger.debug("Getting usage history: allocation=$allocationId, tenant=$tenantId")

        val events = meteringService.getUsageHistory(
            allocationId = allocationId,
            tenantId = tenantId,
            startTime = from,
            endTime = to,
            limit = limit
        )

        val response = UsageHistoryResponse(
            allocationId = allocationId,
            totalCount = events.size,
            events = events.map { event ->
                UsageEventResponse(
                    id = event.id,
                    amount = event.amount,
                    occurredAt = event.occurredAt,
                    eventType = event.eventType,
                    endUserId = event.endUserId,
                    metadata = event.metadata
                )
            }
        )

        return ResponseEntity.ok(response)
    }

    /**
     * Get usage summary for an allocation
     */
    @GetMapping("/allocations/{allocationId}/summary")
    suspend fun getUsageSummary(
        @PathVariable allocationId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant,
        authentication: Authentication
    ): ResponseEntity<UsageSummary> {
        val tenantId = extractTenantId(authentication)

        logger.debug("Getting usage summary: allocation=$allocationId, tenant=$tenantId")

        val summary = meteringService.getUsageSummary(
            allocationId = allocationId,
            tenantId = tenantId,
            startTime = from,
            endTime = to
        )

        return ResponseEntity.ok(summary)
    }

    /**
     * Extract tenant ID from authentication
     */
    private fun extractTenantId(authentication: Authentication): String {
        // In production, extract from JWT claims
        // For now, use a placeholder
        return authentication.principal.toString()
    }
}

/**
 * Response DTOs
 */
data class UsageHistoryResponse(
    val allocationId: UUID,
    val totalCount: Int,
    val events: List<UsageEventResponse>
)

data class UsageEventResponse(
    val id: UUID,
    val amount: java.math.BigDecimal,
    val occurredAt: Instant,
    val eventType: String,
    val endUserId: String,
    val metadata: Map<String, Any>
)
