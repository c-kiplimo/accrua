package com.accrua.domain.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

/**
 * Unit tests for Allocation domain model
 */
class AllocationTest {

    @Test
    fun `should create valid allocation`() {
        // Given
        val tenantId = "tenant-1"
        val sponsorId = "sponsor-1"
        val providerId = "provider-1"
        val meterId = UUID.randomUUID()
        val totalAmount = BigDecimal("10000.00")

        // When
        val allocation = Allocation.create(
            tenantId = tenantId,
            sponsorId = sponsorId,
            providerId = providerId,
            meterId = meterId,
            totalAmount = totalAmount
        )

        // Then
        allocation shouldNotBe null
        allocation.tenantId shouldBe tenantId
        allocation.sponsorId shouldBe sponsorId
        allocation.providerId shouldBe providerId
        allocation.totalAmount shouldBe totalAmount
        allocation.usedAmount shouldBe BigDecimal.ZERO
        allocation.status shouldBe AllocationStatus.ACTIVE
        allocation.remainingAmount shouldBe totalAmount
        allocation.utilizationRate shouldBe 0.0
    }

    @Test
    fun `should reject invalid allocation with blank tenant`() {
        // Given/When/Then
        shouldThrow<IllegalArgumentException> {
            Allocation.create(
                tenantId = "",
                sponsorId = "sponsor-1",
                providerId = "provider-1",
                meterId = UUID.randomUUID(),
                totalAmount = BigDecimal("10000")
            )
        }
    }

    @Test
    fun `should reject invalid allocation with negative amount`() {
        // Given/When/Then
        shouldThrow<IllegalArgumentException> {
            Allocation.create(
                tenantId = "tenant-1",
                sponsorId = "sponsor-1",
                providerId = "provider-1",
                meterId = UUID.randomUUID(),
                totalAmount = BigDecimal("-100")
            )
        }
    }

    @Test
    fun `should apply usage and update balance`() {
        // Given
        val allocation = createTestAllocation(totalAmount = BigDecimal("1000"))
        val usageAmount = BigDecimal("100")

        // When
        val updated = allocation.applyUsage(usageAmount)

        // Then
        updated.usedAmount shouldBe BigDecimal("100")
        updated.remainingAmount shouldBe BigDecimal("900")
        updated.utilizationRate shouldBe 0.1
        updated.status shouldBe AllocationStatus.ACTIVE
        updated.version shouldBe 1
    }

    @Test
    fun `should mark as depleted when fully consumed`() {
        // Given
        val allocation = createTestAllocation(totalAmount = BigDecimal("1000"))
            .applyUsage(BigDecimal("900"))

        // When
        val updated = allocation.applyUsage(BigDecimal("100"))

        // Then
        updated.usedAmount shouldBe BigDecimal("1000")
        updated.remainingAmount shouldBe BigDecimal.ZERO
        updated.status shouldBe AllocationStatus.DEPLETED
    }

    @Test
    fun `should reject usage when allocation is depleted`() {
        // Given
        val allocation = createTestAllocation(totalAmount = BigDecimal("1000"))
            .applyUsage(BigDecimal("1000"))

        // When/Then
        shouldThrow<IllegalArgumentException> {
            allocation.applyUsage(BigDecimal("100"))
        }
    }

    @Test
    fun `should reject usage exceeding remaining balance`() {
        // Given
        val allocation = createTestAllocation(totalAmount = BigDecimal("1000"))
            .applyUsage(BigDecimal("900"))

        // When/Then
        shouldThrow<IllegalArgumentException> {
            allocation.applyUsage(BigDecimal("200"))
        }
    }

    @Test
    fun `should apply decay and reduce balance`() {
        // Given
        val allocation = createTestAllocation(totalAmount = BigDecimal("1000"))
        val decayAmount = BigDecimal("100")

        // When
        val updated = allocation.applyDecay(decayAmount)

        // Then
        updated.totalAmount shouldBe BigDecimal("900")
        updated.remainingAmount shouldBe BigDecimal("900")
    }

    @Test
    fun `should mark as expired when decay consumes all remaining balance`() {
        // Given
        val allocation = createTestAllocation(totalAmount = BigDecimal("1000"))
            .applyUsage(BigDecimal("500"))

        // When
        val updated = allocation.applyDecay(BigDecimal("500"))

        // Then
        updated.status shouldBe AllocationStatus.EXPIRED
        updated.remainingAmount shouldBe BigDecimal.ZERO
    }

    @Test
    fun `should detect expired allocation`() {
        // Given
        val now = Instant.now()
        val past = now.minus(1, ChronoUnit.DAYS)

        val allocation = createTestAllocation(
            totalAmount = BigDecimal("1000"),
            expiresAt = past
        )

        // When/Then
        allocation.isExpired(now) shouldBe true
    }

    @Test
    fun `should not accept usage when expired`() {
        // Given
        val now = Instant.now()
        val past = now.minus(1, ChronoUnit.DAYS)

        val allocation = createTestAllocation(
            totalAmount = BigDecimal("1000"),
            expiresAt = past
        )

        // When/Then
        allocation.canAcceptUsage(BigDecimal("100"), now) shouldBe false
    }

    @Test
    fun `should suspend and reactivate allocation`() {
        // Given
        val allocation = createTestAllocation()

        // When
        val suspended = allocation.suspend()
        val reactivated = suspended.reactivate()

        // Then
        suspended.status shouldBe AllocationStatus.SUSPENDED
        reactivated.status shouldBe AllocationStatus.ACTIVE
    }

    @Test
    fun `should not reactivate non-suspended allocation`() {
        // Given
        val allocation = createTestAllocation()

        // When/Then
        shouldThrow<IllegalArgumentException> {
            allocation.reactivate()
        }
    }

    @Test
    fun `should cancel allocation`() {
        // Given
        val allocation = createTestAllocation()

        // When
        val cancelled = allocation.cancel()

        // Then
        cancelled.status shouldBe AllocationStatus.CANCELLED
    }

    // Helper function to create test allocations
    private fun createTestAllocation(
        tenantId: String = "test-tenant",
        sponsorId: String = "test-sponsor",
        providerId: String = "test-provider",
        meterId: UUID = UUID.randomUUID(),
        totalAmount: BigDecimal = BigDecimal("10000"),
        expiresAt: Instant? = null
    ): Allocation = Allocation.create(
        tenantId = tenantId,
        sponsorId = sponsorId,
        providerId = providerId,
        meterId = meterId,
        totalAmount = totalAmount,
        expiresAt = expiresAt
    )
}
