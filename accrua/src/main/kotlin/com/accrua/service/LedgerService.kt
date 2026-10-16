package com.accrua.service

import com.accrua.domain.model.*
import com.accrua.repository.AllocationRepository
import com.accrua.repository.LedgerEntryRepository
import com.accrua.repository.UsageEventRepository
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * LedgerService manages the multi-view ledger.
 *
 * The ledger provides different views of the same underlying data:
 * - Provider view: Revenue tracking
 * - Sponsor view: Spend tracking
 * - End user view: Usage tracking
 *
 * Design patterns:
 * - Event sourcing: All entries are immutable
 * - CQRS: Read models derived from event log
 * - Multi-tenant: Row-level security
 */
@Service
class LedgerService(
    private val ledgerEntryRepository: LedgerEntryRepository,
    private val allocationRepository: AllocationRepository,
    private val usageEventRepository: UsageEventRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Record usage in the ledger
     */
    @Transactional
    suspend fun recordUsage(
        event: UsageEvent,
        allocation: Allocation
    ): LedgerEntry {
        logger.debug("Recording usage in ledger: event=${event.id}, allocation=${allocation.id}")

        val entry = LedgerEntry.forUsage(
            tenantId = event.tenantId,
            allocationId = event.allocationId,
            eventId = event.id,
            amount = event.amount,
            sponsorId = allocation.sponsorId,
            endUserId = event.endUserId,
            metadata = event.metadata
        )

        return ledgerEntryRepository.save(entry)
    }

    /**
     * Record allocation in the ledger
     */
    @Transactional
    suspend fun recordAllocation(allocation: Allocation): LedgerEntry {
        logger.debug("Recording allocation in ledger: ${allocation.id}")

        val entry = LedgerEntry.forAllocation(
            tenantId = allocation.tenantId,
            allocationId = allocation.id,
            amount = allocation.totalAmount,
            sponsorId = allocation.sponsorId,
            providerId = allocation.providerId,
            metadata = allocation.metadata
        )

        return ledgerEntryRepository.save(entry)
    }

    /**
     * Record decay in the ledger
     */
    @Transactional
    suspend fun recordDecay(
        allocation: Allocation,
        decayAmount: BigDecimal,
        decayEventId: UUID
    ): LedgerEntry {
        logger.debug("Recording decay in ledger: allocation=${allocation.id}, amount=$decayAmount")

        val entry = LedgerEntry.forDecay(
            tenantId = allocation.tenantId,
            allocationId = allocation.id,
            eventId = decayEventId,
            amount = decayAmount,
            sponsorId = allocation.sponsorId,
            metadata = mapOf(
                "decay_policy" to (allocation.decayPolicy ?: "unknown"),
                "reason" to "time_based_decay"
            )
        )

        return ledgerEntryRepository.save(entry)
    }

    /**
     * Record manual adjustment
     */
    @Transactional
    suspend fun recordAdjustment(
        tenantId: String,
        allocationId: UUID,
        amount: BigDecimal,
        reason: String
    ): LedgerEntry {
        logger.info("Recording adjustment: allocation=$allocationId, amount=$amount, reason=$reason")

        val entry = LedgerEntry.forAdjustment(
            tenantId = tenantId,
            allocationId = allocationId,
            eventId = UUID.randomUUID(),
            amount = amount,
            reason = reason
        )

        return ledgerEntryRepository.save(entry)
    }

    /**
     * Get provider view (revenue tracking)
     */
    suspend fun getProviderView(
        providerId: String,
        tenantId: String,
        startTime: Instant? = null,
        endTime: Instant? = null
    ): BalanceView.Provider {
        logger.debug("Generating provider view: provider=$providerId, tenant=$tenantId")

        // Get all allocations for this provider
        val allocations = allocationRepository.findByTenantId(tenantId)
            .toList()
            .filter { it.providerId == providerId }

        // Calculate total revenue (all allocations created)
        val totalRevenue = allocations.sumOf { it.totalAmount }

        // Calculate breakage revenue (decayed/expired unused amounts)
        val breakageRevenue = calculateBreakageRevenue(allocations)

        // Calculate active allocations
        val activeAllocations = allocations
            .filter { it.status == AllocationStatus.ACTIVE }
            .sumOf { it.remainingAmount }

        // TODO: Calculate pending settlement (requires invoice data)
        val pendingSettlement = BigDecimal.ZERO

        return BalanceView.Provider(
            totalRevenue = totalRevenue,
            revenueByPeriod = emptyMap(), // TODO: Implement time-series aggregation
            breakageRevenue = breakageRevenue,
            activeAllocations = activeAllocations,
            pendingSettlement = pendingSettlement
        )
    }

    /**
     * Get sponsor view (spend tracking)
     */
    suspend fun getSponsorView(
        sponsorId: String,
        tenantId: String,
        startTime: Instant? = null,
        endTime: Instant? = null
    ): BalanceView.Sponsor {
        logger.debug("Generating sponsor view: sponsor=$sponsorId, tenant=$tenantId")

        // Get all allocations for this sponsor
        val allocations = allocationRepository.findBySponsorId(sponsorId)
            .toList()
            .filter { it.tenantId == tenantId }

        // Calculate total spend (all usage)
        val totalSpend = allocations.sumOf { it.usedAmount }

        // Calculate active balance
        val activeBalance = allocations
            .filter { it.status == AllocationStatus.ACTIVE }
            .sumOf { it.remainingAmount }

        // Calculate utilization rate
        val totalAllocated = allocations.sumOf { it.totalAmount }
        val utilizationRate = if (totalAllocated > BigDecimal.ZERO) {
            (totalSpend / totalAllocated).toDouble()
        } else {
            0.0
        }

        // Get top consumers
        val topConsumers = getTopConsumers(allocations, limit = 10)

        return BalanceView.Sponsor(
            totalSpend = totalSpend,
            spendByPeriod = emptyMap(), // TODO: Implement time-series aggregation
            activeBalance = activeBalance,
            utilizationRate = utilizationRate,
            topConsumers = topConsumers,
            projectedDepletion = null // TODO: Implement ML-based forecasting
        )
    }

    /**
     * Get end user view (usage tracking)
     */
    suspend fun getEndUserView(
        endUserId: String,
        tenantId: String,
        allocationId: UUID? = null
    ): BalanceView.EndUser {
        logger.debug("Generating end user view: user=$endUserId, tenant=$tenantId")

        // Get user's allocations
        val allocations = if (allocationId != null) {
            listOfNotNull(allocationRepository.findByIdAndTenantId(allocationId, tenantId))
        } else {
            allocationRepository.findByTenantId(tenantId).toList()
        }

        // Calculate available balance
        val availableBalance = allocations
            .filter { it.status == AllocationStatus.ACTIVE }
            .sumOf { it.remainingAmount }

        // Get usage this period (current month)
        val now = Instant.now()
        val startOfMonth = now.atZone(java.time.ZoneOffset.UTC)
            .withDayOfMonth(1)
            .withHour(0)
            .withMinute(0)
            .withSecond(0)
            .toInstant()

        val usedThisPeriod = usageEventRepository.findByEndUserIdAndTenantId(endUserId, tenantId, 1000)
            .toList()
            .filter { it.occurredAt >= startOfMonth }
            .sumOf { it.amount }

        // Get usage history
        val usageHistory = usageEventRepository.findByEndUserIdAndTenantId(endUserId, tenantId, 50)
            .toList()
            .map { event ->
                UsageRecord(
                    eventId = event.id,
                    amount = event.amount,
                    timestamp = event.occurredAt,
                    eventType = event.eventType,
                    metadata = event.metadata
                )
            }

        return BalanceView.EndUser(
            availableBalance = availableBalance,
            usedThisPeriod = usedThisPeriod,
            remainingQuota = availableBalance,
            usageHistory = usageHistory,
            projectedDepletion = null // TODO: Implement forecasting
        )
    }

    /**
     * Get ledger summary for an allocation
     */
    suspend fun getLedgerSummary(
        allocationId: UUID,
        tenantId: String,
        startDate: Instant,
        endDate: Instant
    ): LedgerSummary {
        logger.debug("Generating ledger summary: allocation=$allocationId")

        // Validate allocation
        val allocation = allocationRepository.findByIdAndTenantId(allocationId, tenantId)
            ?: throw IllegalArgumentException("Allocation not found or unauthorized")

        // Get all entries in range
        val entries = ledgerEntryRepository.findByAllocationIdAndTimeRange(
            allocationId = allocationId,
            startTime = startDate,
            endTime = endDate
        ).toList()

        // Calculate totals
        val totalCredits = entries.filter { it.isCredit }.sumOf { it.amount }
        val totalDebits = entries.filter { it.isDebit }.sumOf { it.amount.abs() }
        val netChange = totalCredits - totalDebits

        // Group by event type
        val byEventType = entries.groupBy { it.eventType }
            .mapValues { (_, entries) -> entries.sumOf { it.amount } }

        // Calculate opening balance (balance at start date)
        val openingBalance = calculateBalanceAtTime(allocationId, startDate)
        val closingBalance = openingBalance + netChange

        return LedgerSummary(
            allocationId = allocationId,
            startDate = startDate,
            endDate = endDate,
            openingBalance = openingBalance,
            closingBalance = closingBalance,
            totalCredits = totalCredits,
            totalDebits = totalDebits,
            netChange = netChange,
            entryCount = entries.size,
            byEventType = byEventType
        )
    }

    /**
     * Calculate balance at a specific point in time
     */
    private suspend fun calculateBalanceAtTime(
        allocationId: UUID,
        timestamp: Instant
    ): BigDecimal {
        val entries = ledgerEntryRepository.findByAllocationIdAndTimeRange(
            allocationId = allocationId,
            startTime = Instant.EPOCH,
            endTime = timestamp
        ).toList()

        val credits = entries.filter { it.isCredit }.sumOf { it.amount }
        val debits = entries.filter { it.isDebit }.sumOf { it.amount.abs() }

        return credits - debits
    }

    /**
     * Calculate breakage revenue (expired/decayed unused allocations)
     */
    private suspend fun calculateBreakageRevenue(allocations: List<Allocation>): BigDecimal {
        return allocations
            .filter { it.status == AllocationStatus.EXPIRED || it.status == AllocationStatus.DEPLETED }
            .sumOf { allocation ->
                // Breakage = amount that was allocated but never used and then expired
                val entries = ledgerEntryRepository.findByAllocationIdOrderByTimestampDesc(allocation.id)
                    .toList()

                val decayed = entries
                    .filter { it.eventType == LedgerEventType.DECAY || it.eventType == LedgerEventType.EXPIRATION }
                    .sumOf { it.amount.abs() }

                decayed
            }
    }

    /**
     * Get top consumers for a set of allocations
     */
    private suspend fun getTopConsumers(
        allocations: List<Allocation>,
        limit: Int = 10
    ): List<EndUserConsumption> {
        val allocationIds = allocations.map { it.id }

        // Get all usage events for these allocations
        val usageByUser = mutableMapOf<String, MutableList<UsageEvent>>()

        for (allocationId in allocationIds) {
            val events = usageEventRepository.findByAllocationId(allocationId).toList()
            events.forEach { event ->
                usageByUser.getOrPut(event.endUserId) { mutableListOf() }.add(event)
            }
        }

        // Calculate totals and percentages
        val totalUsage = usageByUser.values.flatten().sumOf { it.amount }

        return usageByUser.entries
            .map { (userId, events) ->
                val userTotal = events.sumOf { it.amount }
                val percentage = if (totalUsage > BigDecimal.ZERO) {
                    (userTotal / totalUsage).toDouble()
                } else {
                    0.0
                }

                EndUserConsumption(
                    userId = userId,
                    totalUsage = userTotal,
                    percentage = percentage,
                    lastActivityAt = events.maxOfOrNull { it.occurredAt } ?: Instant.now()
                )
            }
            .sortedByDescending { it.totalUsage }
            .take(limit)
    }
}
