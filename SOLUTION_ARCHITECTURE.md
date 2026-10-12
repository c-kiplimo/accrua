# Accrua — Solution Architecture Document
## Usage-Based Billing & Metering Infrastructure

**Version:** 1.0
**Date:** 2026-10-12
**Author:** Senior Backend Engineer
**Tech Stack:** Kotlin, Spring Boot WebFlux, R2DBC, PostgreSQL, Kafka, Redis

---

## Table of Contents
1. [Executive Summary](#executive-summary)
2. [Problem Statement](#problem-statement)
3. [System Overview](#system-overview)
4. [Architectural Principles](#architectural-principles)
5. [Technical Architecture](#technical-architecture)
6. [Component Design](#component-design)
7. [Data Models](#data-models)
8. [Event Flow & Processing](#event-flow--processing)
9. [Scalability & Performance](#scalability--performance)
10. [Reliability & Fault Tolerance](#reliability--fault-tolerance)
11. [Security](#security)
12. [Monitoring & Observability](#monitoring--observability)
13. [Deployment Architecture](#deployment-architecture)
14. [API Design](#api-design)
15. [Trade-offs & Decisions](#trade-offs--decisions)

---

## Executive Summary

Accrua is a production-grade, event-driven metering and billing infrastructure designed to solve the fundamental limitations of fixed-cycle allocation models. The system enables real-time consumption tracking, dynamic resource management, and automated settlement for any finite, replenishable resource.

### Key Capabilities
- **Event-driven metering pipeline** with exactly-once semantics
- **Time-decaying resource management** with configurable expiration policies
- **Dynamic rules engine** for real-time consumption governance
- **Multi-tenant ledger** with role-based views (Provider, Sponsor, End User)
- **Invoice-based settlement** with configurable billing cycles
- **Anomaly detection** for consumption pattern analysis
- **Horizontal scalability** supporting millions of events per second

### Target Market
- API/Developer tool companies (usage-based pricing)
- Cloud infrastructure providers (consumption billing)
- Telecommunications (data/credit bundles)
- AI/LLM platforms (token-based billing)
- IoT platforms (device data consumption)

---

## Problem Statement

### Current State: Flat-Cycle Allocation Model
Traditional billing systems allocate a fixed amount per period regardless of actual consumption patterns:

```
┌─────────────────────────────────────────────┐
│ Monthly Cycle: 10,000 units allocated      │
├─────────────────────────────────────────────┤
│ Day 1-15:  User consumes 9,500 units       │
│ Day 16:    User blocked (under-provisioning)│
│ Day 17-30: Manual top-up required          │
└─────────────────────────────────────────────┘

Alternative scenario:
┌─────────────────────────────────────────────┐
│ Monthly Cycle: 10,000 units allocated      │
├─────────────────────────────────────────────┤
│ Day 1-30:  User consumes 1,200 units       │
│ End of month: 8,800 units expire unused    │
│              (over-provisioning/waste)      │
└─────────────────────────────────────────────┘
```

### Failure Modes
1. **Under-provisioning**: Heavy users hit limits mid-cycle, causing service disruption
2. **Over-provisioning**: Light users waste allocated resources with no visibility
3. **Billing Opacity**: No granular visibility into actual consumption patterns
4. **Manual Intervention**: Ad-hoc top-ups require manual processes
5. **Revenue Leakage**: Breakage revenue not properly tracked or optimized

### Solution: Event-Driven Metering
Accrua replaces fixed cycles with continuous, event-driven metering:
- Usage tracked as discrete events in real-time
- Balance recalculated dynamically against configurable rules
- Time-based resource decay for unused allocations
- Automated settlement based on actual consumption
- Multi-dimensional analytics for optimization

---

## System Overview

### High-Level Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                     External Systems                           │
├───────────┬────────────────┬──────────────┬───────────────────┤
│  Clients  │   Providers    │   Sponsors   │   Admin Portal    │
└─────┬─────┴────────┬───────┴──────┬───────┴──────┬────────────┘
      │              │              │              │
      │ REST/gRPC    │              │              │
      ▼              ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      API Gateway Layer                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│  │ Rate Limiter │  │  AuthN/AuthZ │  │   Validation │         │
│  └──────────────┘  └──────────────┘  └──────────────┘         │
└────────────┬────────────────────────────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Application Layer (WebFlux)                  │
├──────────────┬──────────────┬──────────────┬──────────────────┤
│   Metering   │   Billing    │   Ledger     │   Analytics      │
│   Service    │   Service    │   Service    │   Service        │
└──────┬───────┴──────┬───────┴──────┬───────┴──────┬───────────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Event Processing Layer                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                  Kafka Event Streams                      │  │
│  ├──────────────┬──────────────┬──────────────┬─────────────┤  │
│  │ usage-events │ decay-events │ settlement-  │ anomaly-    │  │
│  │              │              │ events       │ events      │  │
│  └──────────────┴──────────────┴──────────────┴─────────────┘  │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │         Event Processors (Kafka Streams)                 │  │
│  ├──────────────┬──────────────┬──────────────┬─────────────┤  │
│  │ Idempotency  │ Balance Calc │ Time Decay   │ Anomaly     │  │
│  │ Filter       │ Processor    │ Processor    │ Detector    │  │
│  └──────────────┴──────────────┴──────────────┴─────────────┘  │
└─────────────────────────────────────────────────────────────────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Data Layer (R2DBC)                         │
├──────────────┬──────────────┬──────────────┬──────────────────┤
│  PostgreSQL  │    Redis     │  TimescaleDB │   ClickHouse     │
│  (Ledger)    │  (Cache/Lock)│  (Time Series)│  (Analytics)    │
└──────────────┴──────────────┴──────────────┴──────────────────┘
```

### Core Components

1. **Metering Service**: Event ingestion, validation, and idempotency
2. **Billing Service**: Settlement, invoicing, and payment processing
3. **Ledger Service**: Multi-view balance management and transaction history
4. **Analytics Service**: Consumption patterns, anomaly detection, forecasting
5. **Rules Engine**: Dynamic consumption policies and limit enforcement
6. **Time Decay Engine**: Resource expiration and reclamation

---

## Architectural Principles

### 1. Event Sourcing
All state changes captured as immutable events. The ledger is derived from the event log, enabling:
- Complete audit trail
- Temporal queries (balance at any point in time)
- Event replay for debugging/reconciliation
- Easy integration with external systems

### 2. CQRS (Command Query Responsibility Segregation)
Separate models for writes (commands) and reads (queries):
- **Write Model**: Optimized for event ingestion and validation
- **Read Model**: Materialized views for fast queries
- Enables independent scaling of read/write workloads

### 3. Reactive Programming
Non-blocking I/O throughout the stack:
- Spring WebFlux for HTTP layer
- R2DBC for database access
- Reactive Kafka for event streaming
- Maximizes throughput and resource efficiency

### 4. Domain-Driven Design
Clear bounded contexts with ubiquitous language:
- **Metering Context**: Events, Meters, Usage Records
- **Billing Context**: Invoices, Charges, Payments
- **Ledger Context**: Balances, Transactions, Allocations
- **Rules Context**: Policies, Limits, Quotas

### 5. Idempotency by Design
All operations are idempotent:
- Deduplication at ingestion (event ID)
- Upsert semantics for state updates
- Prevents double-counting and duplicate charges

### 6. Time-Aware Architecture
First-class support for temporal operations:
- Event timestamps (occurrence time vs. processing time)
- Time-windowed aggregations
- Expiration policies with configurable decay curves
- Billing cycle management

### 7. Multi-Tenancy
Tenant isolation at all layers:
- Row-level security in database
- Tenant-scoped Kafka partitions
- Separate rate limits per tenant
- Isolated failure domains

---

## Technical Architecture

### Technology Stack Rationale

#### Kotlin + Spring Boot WebFlux
**Why Kotlin:**
- Null-safety eliminates NPEs (critical for financial systems)
- Coroutines provide elegant reactive programming
- Data classes reduce boilerplate
- Strong type system catches errors at compile time
- Excellent Java interop for ecosystem access

**Why Reactive (WebFlux):**
- Non-blocking I/O maximizes throughput
- Backpressure handling prevents overload
- Efficient resource utilization (fewer threads)
- Natural fit for event-driven architecture

#### R2DBC (Reactive Relational Database Connectivity)
- Fully reactive database access
- Connection pooling with backpressure
- Integration with Spring Data R2DBC
- Supports PostgreSQL, H2 for testing

**Why PostgreSQL:**
- ACID guarantees for financial data
- JSON/JSONB for flexible event storage
- Strong consistency model
- Mature tooling and ops experience
- TimescaleDB extension for time-series data

#### Apache Kafka
- Distributed event streaming platform
- Exactly-once semantics (idempotent producer + transactions)
- Partitioning for horizontal scalability
- Retention policies for event replay
- Kafka Streams for stateful processing

#### Redis
- Distributed locking (Redisson)
- Rate limiting (sliding window algorithm)
- Caching (balance snapshots, computed aggregations)
- Idempotency tracking (bloom filter + TTL)

#### ClickHouse (Optional, for Analytics)
- Columnar storage for analytical queries
- Sub-second query performance on billions of rows
- Materialized views for pre-aggregation
- Time-series optimizations

---

## Component Design

### 1. Metering Service

**Responsibilities:**
- Ingest usage events from clients
- Validate event schema and business rules
- Ensure idempotency (no double-counting)
- Publish validated events to Kafka

**Key Classes:**
```kotlin
// Event ingestion endpoint
@RestController
@RequestMapping("/api/v1/events")
class MeteringController(
    private val meteringService: MeteringService
) {
    @PostMapping
    suspend fun ingestEvent(@RequestBody event: UsageEvent): EventReceipt
}

// Core metering service
@Service
class MeteringService(
    private val idempotencyService: IdempotencyService,
    private val eventPublisher: EventPublisher,
    private val validator: EventValidator
) {
    suspend fun ingest(event: UsageEvent): EventReceipt {
        // 1. Idempotency check
        if (idempotencyService.isDuplicate(event.id)) {
            return idempotencyService.getReceipt(event.id)
        }

        // 2. Validate
        validator.validate(event)

        // 3. Publish to Kafka
        val receipt = eventPublisher.publish(event)

        // 4. Store receipt
        idempotencyService.storeReceipt(event.id, receipt)

        return receipt
    }
}
```

**Idempotency Strategy:**
1. Client provides unique `event_id` (UUID)
2. First check Redis bloom filter (probabilistic, fast)
3. If potential duplicate, check PostgreSQL (definitive)
4. Store event hash + receipt for deduplication window (7 days)

**Event Validation Rules:**
- Schema validation (JSON Schema or Protobuf)
- Timestamp validation (not in future, not too old)
- Tenant authorization (event belongs to authenticated tenant)
- Resource existence (meter exists, allocation active)
- Business rules (consumption within limits)

---

### 2. Event Processing Layer

**Architecture:**
```
┌─────────────────────────────────────────────────────┐
│              Kafka Topics Topology                  │
├─────────────────────────────────────────────────────┤
│                                                     │
│  usage-events (partitioned by tenant_id)           │
│        ↓                                            │
│  ┌──────────────────┐                              │
│  │ Idempotency Filter│  (stateful, dedup store)    │
│  └────────┬─────────┘                              │
│           ↓                                         │
│  ┌──────────────────┐                              │
│  │ Balance Processor │  (stateful, balance store)  │
│  └────────┬─────────┘                              │
│           ├────────────────┬─────────────────┐     │
│           ↓                ↓                 ↓     │
│  usage-ledger      balance-snapshots   violations  │
│                                                     │
│  ┌──────────────────┐                              │
│  │ Time Decay Job   │  (scheduled, every hour)     │
│  └────────┬─────────┘                              │
│           ↓                                         │
│  decay-events                                       │
└─────────────────────────────────────────────────────┘
```

**Balance Calculation Processor:**
```kotlin
@Component
class BalanceProcessor : KafkaStreamsProcessor {

    fun buildTopology(): Topology {
        val builder = StreamsBuilder()

        // 1. Read validated usage events
        val usageStream: KStream<String, UsageEvent> =
            builder.stream("usage-events")

        // 2. Group by allocation_id
        val balanceTable: KTable<String, Balance> = usageStream
            .groupBy { _, event -> event.allocationId }
            .aggregate(
                initializer = { Balance.zero() },
                aggregator = { _, event, balance ->
                    balance.applyUsage(event)
                },
                materialized = Materialized.`as`("balance-store")
                    .withValueSerde(balanceSerde)
            )

        // 3. Detect violations (negative balance, over-limit)
        balanceTable.toStream()
            .filter { _, balance -> balance.isViolated() }
            .to("violations")

        // 4. Create snapshots for quick queries
        balanceTable.toStream()
            .to("balance-snapshots")

        return builder.build()
    }
}
```

**Time Decay Engine:**
```kotlin
@Component
class TimeDecayEngine(
    private val allocationRepository: AllocationRepository,
    private val decayPolicyResolver: DecayPolicyResolver,
    private val eventPublisher: EventPublisher
) {

    @Scheduled(cron = "0 0 * * * *") // Every hour
    suspend fun processDecay() {
        val now = Instant.now()

        // Find allocations with active decay policies
        allocationRepository
            .findActiveWithDecay(now)
            .collect { allocation ->
                val policy = decayPolicyResolver.resolve(allocation)
                val decayAmount = policy.calculateDecay(allocation, now)

                if (decayAmount > 0) {
                    val event = DecayEvent(
                        allocationId = allocation.id,
                        amount = decayAmount,
                        timestamp = now,
                        policy = policy.name
                    )

                    eventPublisher.publish(event)
                }
            }
    }
}

// Decay policy examples
interface DecayPolicy {
    fun calculateDecay(allocation: Allocation, now: Instant): BigDecimal
}

class LinearDecayPolicy(
    private val decayRate: BigDecimal // e.g., 1% per day
) : DecayPolicy {
    override fun calculateDecay(allocation: Allocation, now: Instant): BigDecimal {
        val daysSinceLastActivity = Duration.between(
            allocation.lastActivityAt, now
        ).toDays()

        return allocation.balance * decayRate * daysSinceLastActivity.toBigDecimal()
    }
}

class ExpirationPolicy(
    private val expiryDuration: Duration // e.g., 30 days
) : DecayPolicy {
    override fun calculateDecay(allocation: Allocation, now: Instant): BigDecimal {
        val expiresAt = allocation.createdAt + expiryDuration

        return if (now >= expiresAt) {
            allocation.balance // Full decay
        } else {
            BigDecimal.ZERO
        }
    }
}
```

---

### 3. Ledger Service

**Multi-View Ledger Design:**

Different stakeholders need different views of the same underlying data:

```kotlin
// Unified ledger entry (event sourced)
@Table("ledger_entries")
data class LedgerEntry(
    @Id val id: UUID,
    val tenantId: String,
    val allocationId: UUID,
    val eventId: UUID,
    val eventType: EventType,
    val amount: BigDecimal,
    val timestamp: Instant,
    val metadata: Map<String, Any>
)

// Provider view: revenue tracking
data class ProviderLedgerView(
    val totalRevenue: BigDecimal,
    val revenueByPeriod: Map<YearMonth, BigDecimal>,
    val breakageRevenue: BigDecimal, // Expired unused allocations
    val activeAllocations: BigDecimal,
    val pendingSettlement: BigDecimal
)

// Sponsor view: spend tracking
data class SponsorLedgerView(
    val totalSpend: BigDecimal,
    val spendByPeriod: Map<YearMonth, BigDecimal>,
    val activeBalance: BigDecimal,
    val utilizationRate: Double, // Used / Allocated
    val topConsumers: List<EndUserConsumption>
)

// End user view: usage tracking
data class EndUserLedgerView(
    val availableBalance: BigDecimal,
    val usedThisPeriod: BigDecimal,
    val remainingQuota: BigDecimal,
    val usageHistory: List<UsageRecord>,
    val projectedDepletion: Instant? // ML-based forecast
)
```

**Materialized Views:**
```kotlin
@Repository
class LedgerViewRepository(
    private val r2dbcTemplate: R2dbcEntityTemplate
) {

    // Materialized view refreshed on every event
    suspend fun refreshProviderView(tenantId: String) {
        r2dbcTemplate.databaseClient.sql("""
            INSERT INTO provider_ledger_view (tenant_id, total_revenue, ...)
            SELECT
                tenant_id,
                SUM(CASE WHEN event_type = 'CHARGE' THEN amount ELSE 0 END) as total_revenue,
                SUM(CASE WHEN event_type = 'DECAY' THEN amount ELSE 0 END) as breakage_revenue,
                ...
            FROM ledger_entries
            WHERE tenant_id = :tenantId
            GROUP BY tenant_id
            ON CONFLICT (tenant_id)
            DO UPDATE SET
                total_revenue = EXCLUDED.total_revenue,
                ...
        """.trimIndent())
            .bind("tenantId", tenantId)
            .await()
    }
}
```

---

### 4. Rules Engine

**Dynamic Policy Enforcement:**

```kotlin
// Rule definition DSL
data class ConsumptionRule(
    val id: UUID,
    val tenantId: String,
    val name: String,
    val priority: Int,
    val condition: Condition,
    val action: Action,
    val enabled: Boolean
)

sealed class Condition {
    data class DailyLimit(val maxAmount: BigDecimal) : Condition()
    data class MonthlyLimit(val maxAmount: BigDecimal) : Condition()
    data class RatioLimit(val maxRatio: Double) : Condition() // e.g., 80% of allocation
    data class TimeWindow(val window: Duration) : Condition()
    data class Composite(val operator: LogicalOperator, val conditions: List<Condition>) : Condition()
}

sealed class Action {
    object Block : Action()
    object Warn : Action()
    data class ApplyFee(val fee: BigDecimal) : Action()
    data class Notify(val channels: List<NotificationChannel>) : Action()
}

// Rules engine executor
@Service
class RulesEngine(
    private val ruleRepository: RuleRepository,
    private val evaluator: ConditionEvaluator
) {

    suspend fun evaluate(event: UsageEvent, context: EvaluationContext): RuleResult {
        val rules = ruleRepository
            .findByTenant(event.tenantId)
            .filter { it.enabled }
            .sortedBy { it.priority }

        for (rule in rules) {
            if (evaluator.matches(rule.condition, event, context)) {
                return RuleResult(
                    matched = true,
                    rule = rule,
                    action = rule.action
                )
            }
        }

        return RuleResult.noMatch()
    }
}

// Condition evaluator
@Component
class ConditionEvaluator(
    private val usageRepository: UsageRepository
) {

    suspend fun matches(
        condition: Condition,
        event: UsageEvent,
        context: EvaluationContext
    ): Boolean = when (condition) {
        is Condition.DailyLimit -> {
            val today = LocalDate.now()
            val usageToday = usageRepository
                .sumByAllocationAndDate(event.allocationId, today)

            (usageToday + event.amount) > condition.maxAmount
        }

        is Condition.RatioLimit -> {
            val allocation = context.allocation
            val usedRatio = (allocation.used + event.amount) / allocation.total

            usedRatio > condition.maxRatio
        }

        is Condition.Composite -> {
            when (condition.operator) {
                LogicalOperator.AND ->
                    condition.conditions.all { matches(it, event, context) }
                LogicalOperator.OR ->
                    condition.conditions.any { matches(it, event, context) }
            }
        }

        else -> false
    }
}
```

---

### 5. Billing Service (Invoice-Based Settlement)

**Settlement Architecture:**

```kotlin
// Invoice generation (scheduled job)
@Component
class InvoiceGenerator(
    private val ledgerRepository: LedgerRepository,
    private val invoiceRepository: InvoiceRepository,
    private val pricingEngine: PricingEngine
) {

    @Scheduled(cron = "0 0 1 * * *") // Daily at 1 AM
    suspend fun generateInvoices() {
        val yesterday = LocalDate.now().minusDays(1)

        // Find all sponsors with unbilled usage
        ledgerRepository
            .findUnbilledByDate(yesterday)
            .groupBy { it.sponsorId }
            .collect { (sponsorId, entries) ->
                val invoice = createInvoice(sponsorId, entries, yesterday)
                invoiceRepository.save(invoice)
            }
    }

    private suspend fun createInvoice(
        sponsorId: String,
        entries: List<LedgerEntry>,
        billingDate: LocalDate
    ): Invoice {
        val lineItems = entries
            .groupBy { it.meterId }
            .map { (meterId, meterEntries) ->
                val usage = meterEntries.sumOf { it.amount }
                val price = pricingEngine.calculate(meterId, usage)

                InvoiceLineItem(
                    meterId = meterId,
                    quantity = usage,
                    unitPrice = price.unitPrice,
                    totalAmount = price.totalAmount,
                    description = price.description
                )
            }

        return Invoice(
            id = UUID.randomUUID(),
            sponsorId = sponsorId,
            billingDate = billingDate,
            dueDate = billingDate.plusDays(30),
            lineItems = lineItems,
            totalAmount = lineItems.sumOf { it.totalAmount },
            status = InvoiceStatus.PENDING,
            createdAt = Instant.now()
        )
    }
}

// Tiered pricing engine
@Service
class PricingEngine {

    fun calculate(meterId: String, quantity: BigDecimal): PriceCalculation {
        val meter = getMeter(meterId)

        return when (meter.pricingModel) {
            is PricingModel.Flat ->
                calculateFlat(meter.pricingModel, quantity)

            is PricingModel.Tiered ->
                calculateTiered(meter.pricingModel, quantity)

            is PricingModel.Volume ->
                calculateVolume(meter.pricingModel, quantity)
        }
    }

    private fun calculateTiered(
        model: PricingModel.Tiered,
        quantity: BigDecimal
    ): PriceCalculation {
        var remaining = quantity
        var totalCost = BigDecimal.ZERO

        for (tier in model.tiers.sortedBy { it.upTo }) {
            if (remaining <= BigDecimal.ZERO) break

            val tierQuantity = minOf(
                remaining,
                tier.upTo - (quantity - remaining)
            )

            totalCost += tierQuantity * tier.unitPrice
            remaining -= tierQuantity
        }

        return PriceCalculation(
            unitPrice = totalCost / quantity,
            totalAmount = totalCost,
            description = "Tiered pricing: ${model.tiers.size} tiers"
        )
    }
}

// Pricing models
sealed class PricingModel {
    data class Flat(val unitPrice: BigDecimal) : PricingModel()

    data class Tiered(val tiers: List<PricingTier>) : PricingModel()

    data class Volume(
        val breakpoints: Map<BigDecimal, BigDecimal> // quantity -> unit price
    ) : PricingModel()
}

data class PricingTier(
    val upTo: BigDecimal,
    val unitPrice: BigDecimal
)
```

---

### 6. Anomaly Detection Layer

**ML-Based Consumption Anomaly Detection:**

```kotlin
@Service
class AnomalyDetector(
    private val usageRepository: UsageRepository,
    private val alertService: AlertService
) {

    suspend fun detect(event: UsageEvent) {
        val historical = usageRepository
            .findByAllocation(event.allocationId, limit = 1000)
            .toList()

        if (historical.size < 30) return // Insufficient data

        val stats = calculateStats(historical.map { it.amount })

        // Z-score anomaly detection
        val zScore = (event.amount - stats.mean) / stats.stdDev

        if (abs(zScore) > 3.0) { // 3 standard deviations
            alertService.sendAlert(
                Alert(
                    severity = AlertSeverity.HIGH,
                    type = AlertType.CONSUMPTION_ANOMALY,
                    message = "Unusual consumption detected: ${event.amount} " +
                             "(mean: ${stats.mean}, z-score: $zScore)",
                    metadata = mapOf(
                        "event_id" to event.id,
                        "allocation_id" to event.allocationId,
                        "z_score" to zScore
                    )
                )
            )
        }

        // Pattern-based detection (sudden spikes)
        val recentAverage = historical
            .takeLast(10)
            .map { it.amount }
            .average()

        if (event.amount > recentAverage * 5) {
            alertService.sendAlert(
                Alert(
                    severity = AlertSeverity.MEDIUM,
                    type = AlertType.CONSUMPTION_SPIKE,
                    message = "Consumption spike detected: ${event.amount} " +
                             "(recent avg: $recentAverage)",
                    metadata = mapOf(
                        "event_id" to event.id,
                        "spike_ratio" to (event.amount / recentAverage)
                    )
                )
            )
        }
    }

    private data class Stats(
        val mean: Double,
        val stdDev: Double
    )

    private fun calculateStats(values: List<BigDecimal>): Stats {
        val mean = values.map { it.toDouble() }.average()
        val variance = values
            .map { (it.toDouble() - mean).pow(2) }
            .average()
        val stdDev = sqrt(variance)

        return Stats(mean, stdDev)
    }
}
```

---

## Data Models

### Core Domain Entities

```kotlin
// Allocation: A pool of resources allocated to a sponsor
@Table("allocations")
data class Allocation(
    @Id val id: UUID = UUID.randomUUID(),
    val tenantId: String,
    val sponsorId: String,
    val providerId: String,
    val meterId: UUID,
    val totalAmount: BigDecimal,
    val usedAmount: BigDecimal = BigDecimal.ZERO,
    val status: AllocationStatus,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastActivityAt: Instant,
    val decayPolicy: String?,
    val metadata: Map<String, Any> = emptyMap()
) {
    val remainingAmount: BigDecimal
        get() = totalAmount - usedAmount

    val utilizationRate: Double
        get() = if (totalAmount > BigDecimal.ZERO) {
            (usedAmount / totalAmount).toDouble()
        } else 0.0

    fun isExpired(now: Instant): Boolean =
        expiresAt?.let { now >= it } ?: false
}

enum class AllocationStatus {
    ACTIVE,
    DEPLETED,
    EXPIRED,
    SUSPENDED,
    CANCELLED
}

// Usage Event: A discrete consumption event
@Table("usage_events")
data class UsageEvent(
    @Id val id: UUID,
    val tenantId: String,
    val allocationId: UUID,
    val endUserId: String,
    val meterId: UUID,
    val amount: BigDecimal,
    val timestamp: Instant,
    val occurredAt: Instant, // Actual consumption time (may differ from processing time)
    val eventType: String,
    val metadata: Map<String, Any> = emptyMap(),
    val idempotencyKey: String,
    val processedAt: Instant? = null
)

// Meter: Defines what is being metered
@Table("meters")
data class Meter(
    @Id val id: UUID,
    val tenantId: String,
    val name: String,
    val description: String,
    val unit: String, // e.g., "requests", "GB", "tokens"
    val aggregationType: AggregationType,
    val pricingModel: String, // JSON-serialized PricingModel
    val metadata: Map<String, Any> = emptyMap(),
    val createdAt: Instant
)

enum class AggregationType {
    SUM,        // Total consumption
    COUNT,      // Number of events
    MAX,        // Peak consumption
    AVERAGE     // Average consumption
}

// Balance Snapshot: Materialized view for fast queries
@Table("balance_snapshots")
data class BalanceSnapshot(
    @Id val id: UUID,
    val allocationId: UUID,
    val balance: BigDecimal,
    val timestamp: Instant,
    val version: Long // Optimistic locking
)

// Invoice: Settlement document
@Table("invoices")
data class Invoice(
    @Id val id: UUID,
    val sponsorId: String,
    val billingDate: LocalDate,
    val dueDate: LocalDate,
    val totalAmount: BigDecimal,
    val status: InvoiceStatus,
    val lineItems: List<InvoiceLineItem>, // Stored as JSONB
    val createdAt: Instant,
    val paidAt: Instant? = null
)

data class InvoiceLineItem(
    val meterId: UUID,
    val description: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val totalAmount: BigDecimal
)

enum class InvoiceStatus {
    PENDING,
    SENT,
    PAID,
    OVERDUE,
    CANCELLED
}
```

### Database Schema

```sql
-- Allocations table
CREATE TABLE allocations (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    sponsor_id VARCHAR(255) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    meter_id UUID NOT NULL REFERENCES meters(id),
    total_amount NUMERIC(20, 4) NOT NULL,
    used_amount NUMERIC(20, 4) NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    last_activity_at TIMESTAMPTZ NOT NULL,
    decay_policy VARCHAR(255),
    metadata JSONB DEFAULT '{}'::jsonb,

    CONSTRAINT positive_amounts CHECK (total_amount >= 0 AND used_amount >= 0),
    CONSTRAINT usage_within_allocation CHECK (used_amount <= total_amount)
);

CREATE INDEX idx_allocations_tenant ON allocations(tenant_id);
CREATE INDEX idx_allocations_sponsor ON allocations(sponsor_id);
CREATE INDEX idx_allocations_status ON allocations(status);
CREATE INDEX idx_allocations_expires ON allocations(expires_at) WHERE expires_at IS NOT NULL;

-- Usage events table (partitioned by time)
CREATE TABLE usage_events (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    allocation_id UUID NOT NULL REFERENCES allocations(id),
    end_user_id VARCHAR(255) NOT NULL,
    meter_id UUID NOT NULL REFERENCES meters(id),
    amount NUMERIC(20, 4) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    processed_at TIMESTAMPTZ,

    CONSTRAINT positive_amount CHECK (amount >= 0)
) PARTITION BY RANGE (timestamp);

-- Create monthly partitions (example for 2026)
CREATE TABLE usage_events_2026_01 PARTITION OF usage_events
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE usage_events_2026_02 PARTITION OF usage_events
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
-- ... continue for all months

CREATE INDEX idx_usage_events_tenant ON usage_events(tenant_id, timestamp DESC);
CREATE INDEX idx_usage_events_allocation ON usage_events(allocation_id, timestamp DESC);
CREATE INDEX idx_usage_events_idempotency ON usage_events(idempotency_key);

-- Ledger entries (immutable audit log)
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    allocation_id UUID NOT NULL REFERENCES allocations(id),
    event_id UUID NOT NULL REFERENCES usage_events(id),
    event_type VARCHAR(50) NOT NULL,
    amount NUMERIC(20, 4) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb
);

CREATE INDEX idx_ledger_tenant ON ledger_entries(tenant_id, timestamp DESC);
CREATE INDEX idx_ledger_allocation ON ledger_entries(allocation_id, timestamp DESC);

-- Balance snapshots (materialized view)
CREATE TABLE balance_snapshots (
    id UUID PRIMARY KEY,
    allocation_id UUID NOT NULL REFERENCES allocations(id),
    balance NUMERIC(20, 4) NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,

    UNIQUE(allocation_id, timestamp)
);

CREATE INDEX idx_balance_snapshots_allocation ON balance_snapshots(allocation_id, timestamp DESC);

-- Meters table
CREATE TABLE meters (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    unit VARCHAR(50) NOT NULL,
    aggregation_type VARCHAR(50) NOT NULL,
    pricing_model JSONB NOT NULL,
    metadata JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,

    UNIQUE(tenant_id, name)
);

-- Invoices table
CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    sponsor_id VARCHAR(255) NOT NULL,
    billing_date DATE NOT NULL,
    due_date DATE NOT NULL,
    total_amount NUMERIC(20, 4) NOT NULL,
    status VARCHAR(50) NOT NULL,
    line_items JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,

    CONSTRAINT valid_dates CHECK (due_date >= billing_date)
);

CREATE INDEX idx_invoices_sponsor ON invoices(sponsor_id, billing_date DESC);
CREATE INDEX idx_invoices_status ON invoices(status);
CREATE INDEX idx_invoices_due ON invoices(due_date) WHERE status IN ('PENDING', 'SENT');
```

---

## Event Flow & Processing

### End-to-End Event Flow

```
┌─────────────┐
│   Client    │
│  (End User) │
└──────┬──────┘
       │ 1. POST /api/v1/events
       │    { "allocation_id": "...", "amount": 100, ... }
       ▼
┌──────────────────────┐
│  Metering Service    │
│  ┌────────────────┐  │
│  │ Validate Event │  │ 2. Schema + business rules validation
│  └────────┬───────┘  │
│           ▼          │
│  ┌────────────────┐  │
│  │ Idempotency    │  │ 3. Check if duplicate (Redis + PG)
│  │ Check          │  │
│  └────────┬───────┘  │
│           ▼          │
│  ┌────────────────┐  │
│  │ Publish to     │  │ 4. Write to Kafka (exactly-once)
│  │ Kafka          │  │
│  └────────┬───────┘  │
└───────────┼──────────┘
            │
            ▼
┌──────────────────────┐
│   Kafka: usage-events│
└──────────┬───────────┘
           │
           ├──────────────────────────┐
           │                          │
           ▼                          ▼
┌─────────────────────┐    ┌─────────────────────┐
│ Balance Processor   │    │ Anomaly Detector    │
│ (Kafka Streams)     │    │ (Consumer)          │
│                     │    │                     │
│ 5. Aggregate usage  │    │ 7. Statistical      │
│ 6. Update balance   │    │    analysis         │
│    store (state)    │    │ 8. Alert if anomaly │
└──────────┬──────────┘    └─────────────────────┘
           │
           ▼
┌─────────────────────┐
│ Rules Engine        │
│                     │
│ 9. Evaluate policies│
│ 10. Enforce limits  │
└──────────┬──────────┘
           │
           ├──────────────┬──────────────┐
           │              │              │
           ▼              ▼              ▼
┌──────────────┐  ┌──────────┐  ┌──────────────┐
│ Persist to   │  │ Update   │  │ Publish      │
│ PostgreSQL   │  │ Cache    │  │ balance-     │
│ (R2DBC)      │  │ (Redis)  │  │ snapshots    │
└──────────────┘  └──────────┘  └──────────────┘
```

### Critical Path Latency Budget

| Stage | Target Latency | P99 Latency |
|-------|---------------|-------------|
| Event validation | 5ms | 10ms |
| Idempotency check (cache hit) | 2ms | 5ms |
| Idempotency check (cache miss) | 20ms | 50ms |
| Kafka publish | 10ms | 25ms |
| Balance processing | 50ms | 100ms |
| Total (client to acknowledgment) | 25ms | 100ms |

---

## Scalability & Performance

### Horizontal Scalability

**Kafka Partitioning Strategy:**
```
usage-events topic:
  - Partitions: 24 (configurable)
  - Partition key: tenant_id
  - Replication factor: 3
  - Min in-sync replicas: 2

Benefits:
  - Tenant isolation (each tenant's events ordered within partition)
  - Parallel processing (24 consumer instances)
  - Fault tolerance (replicas survive broker failures)
```

**Database Sharding (Future):**
```
Shard key: tenant_id
- Small/medium tenants: Shared database (multi-tenant)
- Large tenants (>1M events/day): Dedicated database (single-tenant)
- Router layer: Determines target shard based on tenant tier
```

### Performance Optimizations

1. **Connection Pooling:**
```kotlin
@Configuration
class R2DBCConfiguration {
    @Bean
    fun connectionFactory(): ConnectionFactory {
        return ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                .option(DRIVER, "postgresql")
                .option(HOST, "localhost")
                .option(PORT, 5432)
                .option(USER, "accrua")
                .option(PASSWORD, "secret")
                .option(DATABASE, "accrua")
                // Pool configuration
                .option(ConnectionFactoryOptions.MAX_SIZE, 50)
                .option(ConnectionFactoryOptions.INITIAL_SIZE, 10)
                .option(ConnectionFactoryOptions.MAX_IDLE_TIME, Duration.ofMinutes(30))
                .option(ConnectionFactoryOptions.MAX_ACQUIRE_TIME, Duration.ofSeconds(3))
                .build()
        )
    }
}
```

2. **Caching Strategy:**
```
L1: Application cache (Caffeine)
  - Balance snapshots (5 min TTL)
  - Meter definitions (15 min TTL)

L2: Distributed cache (Redis)
  - Idempotency keys (7 days TTL)
  - Active allocations (1 hour TTL)
  - Aggregated usage (5 min TTL)

Cache invalidation: Event-driven (Kafka)
```

3. **Batch Processing:**
```kotlin
// Batch event ingestion
@PostMapping("/batch")
suspend fun ingestBatch(@RequestBody events: List<UsageEvent>): BatchReceipt {
    return events
        .asFlow()
        .buffer(capacity = 100) // Buffer up to 100 events
        .map { event -> meteringService.ingest(event) }
        .toList()
        .let { BatchReceipt(it) }
}
```

4. **Read Replicas:**
```
Write traffic → Primary database (leader)
Read traffic (analytics, reports) → Read replicas (followers)
  - Replication lag: <1 second (acceptable for analytics)
  - 3 read replicas for 3x read throughput
```

### Load Testing Benchmarks

**Target Performance:**
- Event ingestion: 10,000 events/sec sustained, 50,000 events/sec peak
- Event-to-balance-update latency: <100ms P99
- Query latency (balance check): <10ms P99
- Concurrent users: 100,000+

**Test Scenarios:**
```kotlin
// K6 load test script (example)
import http from 'k6/http';
import { check, sleep } from 'k6';

export let options = {
    stages: [
        { duration: '2m', target: 100 },   // Ramp up to 100 users
        { duration: '5m', target: 1000 },  // Spike to 1000 users
        { duration: '10m', target: 1000 }, // Sustain 1000 users
        { duration: '2m', target: 0 },     // Ramp down
    ],
    thresholds: {
        'http_req_duration': ['p(99)<100'], // 99% under 100ms
        'http_req_failed': ['rate<0.01'],   // <1% errors
    },
};

export default function() {
    const event = {
        allocation_id: __VU % 10000, // Distribute across allocations
        amount: Math.random() * 100,
        timestamp: new Date().toISOString(),
    };

    const res = http.post('http://localhost:8080/api/v1/events', JSON.stringify(event));

    check(res, {
        'status is 200': (r) => r.status === 200,
        'latency < 100ms': (r) => r.timings.duration < 100,
    });

    sleep(1);
}
```

---

## Reliability & Fault Tolerance

### Exactly-Once Semantics

**Kafka Idempotent Producer:**
```kotlin
@Configuration
class KafkaProducerConfig {
    @Bean
    fun producerFactory(): ProducerFactory<String, UsageEvent> {
        return DefaultKafkaProducerFactory(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to "true", // Exactly-once
                ProducerConfig.ACKS_CONFIG to "all", // Wait for all replicas
                ProducerConfig.RETRIES_CONFIG to Int.MAX_VALUE,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION to "5",
                ProducerConfig.TRANSACTIONAL_ID_CONFIG to "accrua-producer-${UUID.randomUUID()}"
            )
        )
    }
}

// Transactional write
suspend fun publishWithTransaction(event: UsageEvent) {
    kafkaTemplate.executeInTransaction { operations ->
        operations.send("usage-events", event.tenantId, event)
        // Can include database write in same transaction (2PC)
    }
}
```

### Circuit Breaker

```kotlin
@Service
class ExternalBillingService(
    private val httpClient: WebClient,
    @Value("\${billing.api.url}") private val apiUrl: String
) {

    private val circuitBreaker = CircuitBreaker.of(
        "billing-api",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50f)           // Open at 50% failure rate
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .slidingWindowSize(100)               // Last 100 requests
            .permittedNumberOfCallsInHalfOpenState(10)
            .build()
    )

    suspend fun createInvoice(invoice: Invoice): Result<InvoiceResponse> {
        return Try.of {
            circuitBreaker.executeSuspendFunction {
                httpClient.post()
                    .uri("$apiUrl/invoices")
                    .bodyValue(invoice)
                    .retrieve()
                    .awaitBody<InvoiceResponse>()
            }
        }.toResult()
    }
}
```

### Retry Strategy

```kotlin
// Exponential backoff with jitter
suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 3,
    initialDelay: Duration = Duration.ofMillis(100),
    maxDelay: Duration = Duration.ofSeconds(10),
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(maxAttempts - 1) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            // Don't retry on business logic errors
            if (e is BusinessException) throw e

            logger.warn("Attempt ${attempt + 1} failed: ${e.message}")

            // Jitter: randomize delay ±25%
            val jitter = currentDelay.toMillis() * (0.75 + Random.nextDouble() * 0.5)
            delay(jitter.roundToLong())

            currentDelay = minOf(
                Duration.ofMillis((currentDelay.toMillis() * factor).toLong()),
                maxDelay
            )
        }
    }

    // Last attempt (no retry)
    return block()
}
```

### Data Consistency

**Optimistic Locking:**
```kotlin
@Table("balance_snapshots")
data class BalanceSnapshot(
    @Id val id: UUID,
    val allocationId: UUID,
    val balance: BigDecimal,
    @Version val version: Long = 0 // Optimistic lock
)

@Service
class BalanceService(
    private val repository: BalanceSnapshotRepository
) {

    suspend fun updateBalance(allocationId: UUID, delta: BigDecimal) {
        var attempts = 0
        while (attempts < 5) {
            try {
                val snapshot = repository.findByAllocationId(allocationId)
                    ?: throw NotFoundException("Balance not found")

                val updated = snapshot.copy(
                    balance = snapshot.balance + delta,
                    version = snapshot.version + 1 // Increment version
                )

                repository.save(updated) // Throws OptimisticLockException if version changed
                return

            } catch (e: OptimisticLockException) {
                attempts++
                delay(Random.nextLong(10, 100)) // Random backoff
            }
        }

        throw ConcurrentModificationException("Failed to update balance after 5 attempts")
    }
}
```

### Disaster Recovery

**Backup Strategy:**
```
PostgreSQL:
  - Continuous archiving (WAL shipping)
  - Point-in-time recovery (PITR) enabled
  - Daily full backups to S3
  - Retention: 30 days

Kafka:
  - Replication factor: 3
  - Min in-sync replicas: 2
  - Topic retention: 7 days (enough for replay)

Recovery Time Objective (RTO): 1 hour
Recovery Point Objective (RPO): 5 minutes
```

---

## Security

### Authentication & Authorization

**JWT-Based Auth:**
```kotlin
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .authorizeExchange()
                .pathMatchers("/actuator/health").permitAll()
                .pathMatchers("/api/v1/events").hasRole("CLIENT")
                .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyExchange().authenticated()
            .and()
            .oauth2ResourceServer()
                .jwt()
            .and()
            .csrf().disable() // Using token-based auth
            .build()
    }
}

// JWT claims structure
data class JwtClaims(
    val sub: String,        // User ID
    val tenantId: String,   // Tenant isolation
    val roles: List<String>, // RBAC roles
    val exp: Long           // Expiration
)
```

### Multi-Tenancy Isolation

**Row-Level Security:**
```kotlin
// Automatic tenant filtering
@Component
class TenantFilter : WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val tenantId = extractTenantId(exchange)
        return chain.filter(exchange)
            .contextWrite { ctx -> ctx.put("tenantId", tenantId) }
    }
}

// Repository with tenant filtering
@Repository
class TenantAwareRepository(
    private val r2dbcTemplate: R2dbcEntityTemplate
) {

    suspend fun findById(id: UUID): Allocation? {
        val tenantId = coroutineContext[TenantContext]?.tenantId
            ?: throw SecurityException("No tenant context")

        return r2dbcTemplate
            .select(Allocation::class.java)
            .matching(
                Query.query(
                    Criteria.where("id").`is`(id)
                        .and("tenant_id").`is`(tenantId) // Automatic tenant filter
                )
            )
            .awaitFirstOrNull()
    }
}
```

### Data Encryption

**Encryption at Rest:**
```yaml
# PostgreSQL configuration
postgresql:
  ssl_mode: require
  encryption:
    algorithm: AES-256-GCM
    key_rotation: enabled
    kms_provider: AWS KMS

# Sensitive fields (PII)
@Table("allocations")
data class Allocation(
    @Encrypted val sponsorEmail: String,  // Field-level encryption
    @Encrypted val metadata: Map<String, Any>
)
```

**Encryption in Transit:**
```
- TLS 1.3 for all HTTP traffic
- mTLS for service-to-service communication
- Kafka: SSL/TLS encryption
```

### Audit Logging

```kotlin
@Aspect
@Component
class AuditLogger {

    @Around("@annotation(Audited)")
    fun logAuditEvent(joinPoint: ProceedingJoinPoint): Any? {
        val method = (joinPoint.signature as MethodSignature).method
        val annotation = method.getAnnotation(Audited::class.java)

        val auditEvent = AuditEvent(
            timestamp = Instant.now(),
            userId = getCurrentUserId(),
            tenantId = getCurrentTenantId(),
            action = annotation.action,
            resource = annotation.resource,
            details = joinPoint.args.toMap()
        )

        // Write to immutable audit log
        auditRepository.save(auditEvent)

        return joinPoint.proceed()
    }
}

// Usage
@Audited(action = "CREATE", resource = "ALLOCATION")
suspend fun createAllocation(request: CreateAllocationRequest): Allocation {
    // ...
}
```

---

## Monitoring & Observability

### Metrics (Prometheus)

```kotlin
@Configuration
class MetricsConfig {

    @Bean
    fun meterRegistry(): MeterRegistry {
        return PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    }

    @Bean
    fun customMetrics(registry: MeterRegistry): CustomMetrics {
        return CustomMetrics(registry)
    }
}

@Component
class CustomMetrics(private val registry: MeterRegistry) {

    // Counter: Total events processed
    private val eventsProcessed = Counter.builder("accrua.events.processed")
        .description("Total number of usage events processed")
        .tag("status", "success")
        .register(registry)

    // Gauge: Active allocations
    private val activeAllocations = registry.gauge(
        "accrua.allocations.active",
        Tags.empty(),
        AtomicLong(0)
    )

    // Timer: Event processing latency
    private val processingLatency = Timer.builder("accrua.events.processing.duration")
        .description("Event processing latency")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)

    // Distribution summary: Event amounts
    private val eventAmounts = DistributionSummary.builder("accrua.events.amount")
        .description("Distribution of event amounts")
        .baseUnit("units")
        .register(registry)

    fun recordEvent(amount: BigDecimal, processingTime: Duration) {
        eventsProcessed.increment()
        processingLatency.record(processingTime)
        eventAmounts.record(amount.toDouble())
    }
}
```

### Distributed Tracing (OpenTelemetry)

```kotlin
@Configuration
class TracingConfig {

    @Bean
    fun openTelemetry(): OpenTelemetry {
        return OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(
                        BatchSpanProcessor.builder(
                            JaegerGrpcSpanExporter.builder()
                                .setEndpoint("http://jaeger:14250")
                                .build()
                        ).build()
                    )
                    .build()
            )
            .buildAndRegisterGlobal()
    }
}

// Instrumented service
@Service
class MeteringService(
    private val tracer: Tracer
) {

    suspend fun ingest(event: UsageEvent): EventReceipt {
        val span = tracer.spanBuilder("ingest-event")
            .setAttribute("event.id", event.id.toString())
            .setAttribute("tenant.id", event.tenantId)
            .startSpan()

        return span.makeCurrent().use {
            try {
                // Process event
                val receipt = processEvent(event)
                span.setStatus(StatusCode.OK)
                receipt
            } catch (e: Exception) {
                span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
                span.recordException(e)
                throw e
            } finally {
                span.end()
            }
        }
    }
}
```

### Structured Logging

```kotlin
@Configuration
class LoggingConfig {

    @Bean
    fun logstashEncoder(): LogstashEncoder {
        return LogstashEncoder().apply {
            includeContext = true
            includeMdc = true
            includeStructuredArguments = true
        }
    }
}

// Usage
@Service
class MeteringService {

    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun ingest(event: UsageEvent): EventReceipt {
        logger.info(
            "Processing usage event",
            kv("event_id", event.id),
            kv("tenant_id", event.tenantId),
            kv("allocation_id", event.allocationId),
            kv("amount", event.amount)
        )

        // Process...
    }
}

// JSON output:
// {
//   "timestamp": "2026-10-12T10:30:45.123Z",
//   "level": "INFO",
//   "message": "Processing usage event",
//   "event_id": "a1b2c3d4-...",
//   "tenant_id": "tenant-123",
//   "allocation_id": "xyz-...",
//   "amount": 100.50
// }
```

### Health Checks

```kotlin
@Component
class CustomHealthIndicator(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val r2dbcTemplate: R2dbcEntityTemplate
) : HealthIndicator {

    override fun health(): Health {
        return try {
            val checks = listOf(
                checkKafka(),
                checkDatabase(),
                checkRedis()
            )

            if (checks.all { it }) {
                Health.up()
                    .withDetail("kafka", "UP")
                    .withDetail("database", "UP")
                    .withDetail("redis", "UP")
                    .build()
            } else {
                Health.down()
                    .withDetail("kafka", if (checks[0]) "UP" else "DOWN")
                    .withDetail("database", if (checks[1]) "UP" else "DOWN")
                    .withDetail("redis", if (checks[2]) "UP" else "DOWN")
                    .build()
            }
        } catch (e: Exception) {
            Health.down(e).build()
        }
    }

    private suspend fun checkKafka(): Boolean = try {
        kafkaTemplate.send("health-check", "ping").get(1, TimeUnit.SECONDS)
        true
    } catch (e: Exception) {
        false
    }

    private suspend fun checkDatabase(): Boolean = try {
        r2dbcTemplate.databaseClient
            .sql("SELECT 1")
            .fetch()
            .awaitOne()
        true
    } catch (e: Exception) {
        false
    }
}
```

### Alerting Rules (Prometheus Alertmanager)

```yaml
groups:
  - name: accrua_alerts
    interval: 30s
    rules:
      # High error rate
      - alert: HighEventProcessingErrors
        expr: |
          rate(accrua_events_processed_total{status="error"}[5m]) > 0.01
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High event processing error rate"
          description: "Error rate is {{ $value }} errors/sec"

      # High latency
      - alert: HighEventProcessingLatency
        expr: |
          histogram_quantile(0.99,
            rate(accrua_events_processing_duration_bucket[5m])
          ) > 0.5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "High event processing latency"
          description: "P99 latency is {{ $value }}s"

      # Database connection pool exhaustion
      - alert: DatabaseConnectionPoolNearExhaustion
        expr: |
          hikaricp_connections_active / hikaricp_connections_max > 0.8
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Database connection pool near exhaustion"
          description: "{{ $value }}% of connections in use"

      # Kafka consumer lag
      - alert: HighKafkaConsumerLag
        expr: |
          kafka_consumer_lag > 10000
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "High Kafka consumer lag"
          description: "Consumer lag is {{ $value }} messages"
```

---

## Deployment Architecture

### Containerization (Docker)

**Dockerfile:**
```dockerfile
# Multi-stage build for optimized image size
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src

RUN gradle build --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

WORKDIR /app

# Create non-root user
RUN addgroup -S accrua && adduser -S accrua -G accrua
USER accrua

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
```

**Docker Compose (Local Development):**
```yaml
version: '3.8'

services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: dev
      SPRING_R2DBC_URL: r2dbc:postgresql://postgres:5432/accrua
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SPRING_DATA_REDIS_HOST: redis
    depends_on:
      - postgres
      - kafka
      - redis
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 3s
      retries: 3

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: accrua
      POSTGRES_USER: accrua
      POSTGRES_PASSWORD: secret
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./schema.sql:/docker-entrypoint-initdb.d/schema.sql

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    depends_on:
      - zookeeper

  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data

  prometheus:
    image: prom/prometheus:v2.48.0
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus_data:/prometheus

  grafana:
    image: grafana/grafana:10.2.0
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes:
      - grafana_data:/var/lib/grafana
      - ./grafana/dashboards:/etc/grafana/provisioning/dashboards

  jaeger:
    image: jaegertracing/all-in-one:1.52
    ports:
      - "16686:16686"  # Jaeger UI
      - "14250:14250"  # gRPC

volumes:
  postgres_data:
  redis_data:
  prometheus_data:
  grafana_data:
```

### Kubernetes Deployment

**Deployment Manifest:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: accrua-metering
  labels:
    app: accrua
    component: metering
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: accrua
      component: metering
  template:
    metadata:
      labels:
        app: accrua
        component: metering
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: accrua
                    component: metering
                topologyKey: kubernetes.io/hostname

      containers:
        - name: accrua
          image: accrua/metering:1.0.0
          ports:
            - containerPort: 8080
              name: http

          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "production"
            - name: SPRING_R2DBC_URL
              valueFrom:
                secretKeyRef:
                  name: database-credentials
                  key: url
            - name: SPRING_KAFKA_BOOTSTRAP_SERVERS
              value: "kafka-broker-0:9092,kafka-broker-1:9092,kafka-broker-2:9092"

          resources:
            requests:
              memory: "512Mi"
              cpu: "500m"
            limits:
              memory: "2Gi"
              cpu: "2000m"

          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
            failureThreshold: 3

          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 5
            failureThreshold: 3

          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 10"]

---
apiVersion: v1
kind: Service
metadata:
  name: accrua-metering
spec:
  type: ClusterIP
  selector:
    app: accrua
    component: metering
  ports:
    - port: 80
      targetPort: 8080
      name: http

---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: accrua-metering-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: accrua-metering
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
    - type: Pods
      pods:
        metric:
          name: kafka_consumer_lag
        target:
          type: AverageValue
          averageValue: "5000"
```

---

## API Design

### REST API Endpoints

```kotlin
// Event ingestion
POST /api/v1/events
Content-Type: application/json

{
  "allocation_id": "a1b2c3d4-...",
  "end_user_id": "user-123",
  "amount": 100.50,
  "occurred_at": "2026-10-12T10:30:00Z",
  "metadata": {
    "request_id": "req-xyz",
    "resource_type": "api_call"
  }
}

Response 200 OK:
{
  "event_id": "e1f2g3h4-...",
  "status": "accepted",
  "timestamp": "2026-10-12T10:30:00.123Z"
}

// Batch ingestion
POST /api/v1/events/batch
Content-Type: application/json

{
  "events": [
    { "allocation_id": "...", "amount": 100 },
    { "allocation_id": "...", "amount": 200 }
  ]
}

// Balance query
GET /api/v1/allocations/{allocation_id}/balance

Response 200 OK:
{
  "allocation_id": "a1b2c3d4-...",
  "total_amount": 10000.00,
  "used_amount": 3500.00,
  "remaining_amount": 6500.00,
  "utilization_rate": 0.35,
  "status": "active",
  "expires_at": "2026-11-12T00:00:00Z"
}

// Usage history
GET /api/v1/allocations/{allocation_id}/usage
  ?from=2026-10-01T00:00:00Z
  &to=2026-10-12T23:59:59Z
  &limit=100
  &offset=0

Response 200 OK:
{
  "total_count": 1523,
  "items": [
    {
      "event_id": "...",
      "amount": 100.50,
      "timestamp": "2026-10-12T10:30:00Z",
      "end_user_id": "user-123"
    }
  ],
  "pagination": {
    "limit": 100,
    "offset": 0,
    "next": "/api/v1/allocations/.../usage?offset=100"
  }
}

// Create allocation (admin)
POST /api/v1/allocations
Content-Type: application/json

{
  "sponsor_id": "sponsor-abc",
  "meter_id": "meter-xyz",
  "total_amount": 10000.00,
  "expires_at": "2026-12-31T23:59:59Z",
  "decay_policy": "linear_1pct_daily"
}

// Invoices
GET /api/v1/sponsors/{sponsor_id}/invoices
  ?status=pending
  &from=2026-10-01
  &to=2026-10-31

Response 200 OK:
{
  "invoices": [
    {
      "id": "inv-123",
      "billing_date": "2026-10-31",
      "due_date": "2026-11-30",
      "total_amount": 1250.00,
      "status": "pending",
      "line_items": [
        {
          "meter_id": "meter-xyz",
          "description": "API Calls",
          "quantity": 125000,
          "unit_price": 0.01,
          "total_amount": 1250.00
        }
      ]
    }
  ]
}
```

### gRPC API (High-Throughput)

```protobuf
syntax = "proto3";

package accrua.metering.v1;

service MeteringService {
  rpc IngestEvent(UsageEvent) returns (EventReceipt);
  rpc IngestEventBatch(stream UsageEvent) returns (BatchReceipt);
  rpc GetBalance(BalanceRequest) returns (Balance);
}

message UsageEvent {
  string allocation_id = 1;
  string end_user_id = 2;
  double amount = 3;
  google.protobuf.Timestamp occurred_at = 4;
  map<string, string> metadata = 5;
}

message EventReceipt {
  string event_id = 1;
  string status = 2;
  google.protobuf.Timestamp timestamp = 3;
}

message Balance {
  string allocation_id = 1;
  double total_amount = 2;
  double used_amount = 3;
  double remaining_amount = 4;
  double utilization_rate = 5;
  string status = 6;
  google.protobuf.Timestamp expires_at = 7;
}
```

---

## Trade-offs & Decisions

### 1. Invoice-Based vs. Real-Time Debit

**Decision:** Invoice-based settlement

**Rationale:**
- **Pro:** Allows reconciliation and correction before billing
- **Pro:** Better for B2B use cases (standard net-30 terms)
- **Pro:** Reduces database write load (batch writes vs. per-event)
- **Con:** Delayed revenue recognition
- **Con:** Risk of post-consumption disputes

**When to use Real-Time Debit:**
- Prepaid models (gift cards, credits)
- High fraud risk scenarios
- Consumer-facing applications

### 2. PostgreSQL vs. NoSQL

**Decision:** PostgreSQL (with TimescaleDB extension)

**Rationale:**
- **Pro:** ACID guarantees critical for financial data
- **Pro:** Strong consistency model
- **Pro:** Excellent time-series support with TimescaleDB
- **Pro:** Mature ecosystem and tooling
- **Con:** Vertical scaling limitations
- **Mitigation:** Read replicas, partitioning, sharding strategy

**When to use NoSQL:**
- If consistency can be relaxed (eventual consistency acceptable)
- If horizontal scaling is primary requirement
- If schema is highly dynamic

### 3. Kafka vs. RabbitMQ/SQS

**Decision:** Apache Kafka

**Rationale:**
- **Pro:** Exactly-once semantics with idempotent producer
- **Pro:** Event log retention (replay capability)
- **Pro:** Horizontal scalability via partitioning
- **Pro:** Native streaming (Kafka Streams)
- **Con:** Operational complexity (Zookeeper, brokers)
- **Con:** Requires dedicated infrastructure

### 4. Synchronous vs. Asynchronous Event Processing

**Decision:** Asynchronous (Kafka-based)

**Rationale:**
- **Pro:** Decouples ingestion from processing (better availability)
- **Pro:** Natural backpressure handling
- **Pro:** Enables event replay for debugging/reconciliation
- **Con:** Eventual consistency (balance not immediately updated)
- **Mitigation:** Cache-based optimistic balance reads

### 5. Microservices vs. Modular Monolith

**Decision:** Modular monolith (initially), microservices (future)

**Rationale:**
- **Pro:** Simpler deployment and operations (single artifact)
- **Pro:** Easier debugging and testing
- **Pro:** Lower operational overhead for early stage
- **Con:** Harder to scale components independently
- **Migration Path:** Clear bounded contexts enable future extraction

---

## Appendix

### Technology Stack Summary

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Language | Kotlin 1.9+ | Null-safety, coroutines, expressiveness |
| Framework | Spring Boot 3.2 | DI, auto-configuration, ecosystem |
| Reactive | Spring WebFlux | Non-blocking I/O, backpressure |
| Database | PostgreSQL 16 | ACID, JSON, time-series (TimescaleDB) |
| Database Access | R2DBC + Spring Data | Reactive database driver |
| Event Streaming | Apache Kafka 3.6 | Event log, exactly-once semantics |
| Caching | Redis 7 | Distributed cache, locks, rate limiting |
| Analytics (Optional) | ClickHouse 23 | Columnar OLAP, sub-second queries |
| Metrics | Micrometer + Prometheus | Time-series metrics |
| Tracing | OpenTelemetry + Jaeger | Distributed tracing |
| Logging | Logback + Logstash | Structured JSON logging |
| Containerization | Docker | Portable deployment |
| Orchestration | Kubernetes | Container orchestration, auto-scaling |
| Build | Gradle Kotlin DSL | Build automation |
| Testing | JUnit 5, Kotest, Testcontainers | Unit, integration, contract tests |

### Key Performance Indicators (KPIs)

| Metric | Target | Measurement |
|--------|--------|-------------|
| Event ingestion throughput | 10,000 events/sec | Prometheus counter |
| Event-to-balance latency (P99) | <100ms | Distributed tracing |
| Balance query latency (P99) | <10ms | Micrometer timer |
| System availability | 99.9% | Uptime monitoring |
| Data durability | 99.999999% | Kafka replication + PG backups |
| Billing accuracy | 100% | Reconciliation audits |

### Glossary

- **Allocation**: A pool of resources granted to a sponsor organization
- **Sponsor**: An organization that receives resource allocations from a provider
- **End User**: An individual within a sponsor organization who consumes resources
- **Meter**: A definition of what is being measured (e.g., API calls, GB transferred)
- **Usage Event**: A discrete consumption event
- **Ledger Entry**: An immutable record in the financial ledger
- **Decay**: Time-based reduction of unused resource balances
- **Breakage**: Revenue from expired unused resources
- **Settlement**: The process of billing for consumed resources

---

**Document Version:** 1.0
**Last Updated:** 2026-10-12
**Review Cycle:** Quarterly

**Feedback:** For questions or suggestions, please open an issue in the GitHub repository.
