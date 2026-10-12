package com.accrua.domain.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Unit tests for UsageEvent domain model
 */
class UsageEventTest {

    @Test
    fun `should create valid usage event`() {
        // Given
        val tenantId = "tenant-1"
        val allocationId = UUID.randomUUID()
        val endUserId = "user-1"
        val meterId = UUID.randomUUID()
        val amount = BigDecimal("100.50")
        val occurredAt = Instant.now()
        val eventType = "api_call"

        // When
        val event = UsageEvent.create(
            tenantId = tenantId,
            allocationId = allocationId,
            endUserId = endUserId,
            meterId = meterId,
            amount = amount,
            occurredAt = occurredAt,
            eventType = eventType
        )

        // Then
        event shouldNotBe null
        event.tenantId shouldBe tenantId
        event.allocationId shouldBe allocationId
        event.endUserId shouldBe endUserId
        event.meterId shouldBe meterId
        event.amount shouldBe amount
        event.occurredAt shouldBe occurredAt
        event.eventType shouldBe eventType
        event.isProcessed shouldBe false
        event.idempotencyKey shouldNotBe ""
    }

    @Test
    fun `should reject event with blank tenant ID`() {
        shouldThrow<IllegalArgumentException> {
            UsageEvent.create(
                tenantId = "",
                allocationId = UUID.randomUUID(),
                endUserId = "user-1",
                meterId = UUID.randomUUID(),
                amount = BigDecimal("100"),
                occurredAt = Instant.now(),
                eventType = "test"
            )
        }
    }

    @Test
    fun `should reject event with negative amount`() {
        shouldThrow<IllegalArgumentException> {
            UsageEvent.create(
                tenantId = "tenant-1",
                allocationId = UUID.randomUUID(),
                endUserId = "user-1",
                meterId = UUID.randomUUID(),
                amount = BigDecimal("-100"),
                occurredAt = Instant.now(),
                eventType = "test"
            )
        }
    }

    @Test
    fun `should reject event with zero amount`() {
        shouldThrow<IllegalArgumentException> {
            UsageEvent.create(
                tenantId = "tenant-1",
                allocationId = UUID.randomUUID(),
                endUserId = "user-1",
                meterId = UUID.randomUUID(),
                amount = BigDecimal.ZERO,
                occurredAt = Instant.now(),
                eventType = "test"
            )
        }
    }

    @Test
    fun `should reject event with future occurred_at`() {
        val futureTime = Instant.now().plusSeconds(120)

        shouldThrow<IllegalArgumentException> {
            UsageEvent.create(
                tenantId = "tenant-1",
                allocationId = UUID.randomUUID(),
                endUserId = "user-1",
                meterId = UUID.randomUUID(),
                amount = BigDecimal("100"),
                occurredAt = futureTime,
                eventType = "test"
            )
        }
    }

    @Test
    fun `should use provided idempotency key`() {
        // Given
        val customKey = "custom-idempotency-key-123"

        // When
        val event = UsageEvent.create(
            tenantId = "tenant-1",
            allocationId = UUID.randomUUID(),
            endUserId = "user-1",
            meterId = UUID.randomUUID(),
            amount = BigDecimal("100"),
            occurredAt = Instant.now(),
            eventType = "test",
            idempotencyKey = customKey
        )

        // Then
        event.idempotencyKey shouldBe customKey
    }

    @Test
    fun `should mark event as processed`() {
        // Given
        val event = createTestEvent()
        event.isProcessed shouldBe false

        // When
        val processed = event.markProcessed()

        // Then
        processed.isProcessed shouldBe true
        processed.processedAt shouldNotBe null
    }

    @Test
    fun `should calculate event age`() {
        // Given
        val occurredAt = Instant.now().minusSeconds(300) // 5 minutes ago
        val event = createTestEvent(occurredAt = occurredAt)

        // When
        val age = event.getAge()

        // Then
        age shouldBe 300L // ±1 second tolerance
    }

    @Test
    fun `should create batch of events`() {
        // Given
        val tenantId = "tenant-1"
        val allocationId = UUID.randomUUID()
        val endUserId = "user-1"
        val meterId = UUID.randomUUID()
        val eventType = "api_call"

        val events = listOf(
            BigDecimal("100") to Instant.now().minusSeconds(10),
            BigDecimal("200") to Instant.now().minusSeconds(5),
            BigDecimal("150") to Instant.now()
        )

        // When
        val batch = UsageEvent.createBatch(
            tenantId = tenantId,
            allocationId = allocationId,
            endUserId = endUserId,
            meterId = meterId,
            events = events,
            eventType = eventType
        )

        // Then
        batch.size shouldBe 3
        batch[0].amount shouldBe BigDecimal("100")
        batch[1].amount shouldBe BigDecimal("200")
        batch[2].amount shouldBe BigDecimal("150")
    }

    @Test
    fun `should include metadata in event`() {
        // Given
        val metadata = mapOf(
            "request_id" to "req-123",
            "source" to "mobile_app",
            "version" to "1.0.0"
        )

        // When
        val event = createTestEvent(metadata = metadata)

        // Then
        event.metadata shouldBe metadata
        event.metadata["request_id"] shouldBe "req-123"
    }

    // Helper function to create test events
    private fun createTestEvent(
        tenantId: String = "test-tenant",
        allocationId: UUID = UUID.randomUUID(),
        endUserId: String = "test-user",
        meterId: UUID = UUID.randomUUID(),
        amount: BigDecimal = BigDecimal("100"),
        occurredAt: Instant = Instant.now(),
        eventType: String = "test_event",
        metadata: Map<String, Any> = emptyMap()
    ): UsageEvent = UsageEvent.create(
        tenantId = tenantId,
        allocationId = allocationId,
        endUserId = endUserId,
        meterId = meterId,
        amount = amount,
        occurredAt = occurredAt,
        eventType = eventType,
        metadata = metadata
    )
}
