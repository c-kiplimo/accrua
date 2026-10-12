package com.accrua.domain.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * Meter defines what is being measured and how it's priced.
 *
 * Key concepts:
 * - Flexible pricing models (flat, tiered, volume)
 * - Aggregation types (sum, count, max, average)
 * - Extensible metadata for custom configurations
 */
@Table("meters")
data class Meter(
    @Id
    val id: UUID = UUID.randomUUID(),

    val tenantId: String,

    /**
     * Human-readable name
     */
    val name: String,

    /**
     * Detailed description
     */
    val description: String,

    /**
     * Unit of measurement (e.g., "requests", "GB", "tokens", "minutes")
     */
    val unit: String,

    /**
     * How to aggregate events
     */
    val aggregationType: AggregationType,

    /**
     * Pricing model configuration (stored as JSON)
     */
    val pricingModel: PricingModel,

    /**
     * Additional configuration
     */
    val metadata: Map<String, Any> = emptyMap(),

    val createdAt: Instant = Instant.now(),

    val updatedAt: Instant = Instant.now(),

    /**
     * Whether this meter is active
     */
    val active: Boolean = true
) {
    fun validate() {
        require(tenantId.isNotBlank()) { "Tenant ID cannot be blank" }
        require(name.isNotBlank()) { "Name cannot be blank" }
        require(unit.isNotBlank()) { "Unit cannot be blank" }
    }

    fun deactivate(): Meter = copy(
        active = false,
        updatedAt = Instant.now()
    )

    fun updatePricing(newPricingModel: PricingModel): Meter = copy(
        pricingModel = newPricingModel,
        updatedAt = Instant.now()
    )

    companion object {
        fun create(
            tenantId: String,
            name: String,
            description: String,
            unit: String,
            aggregationType: AggregationType,
            pricingModel: PricingModel,
            metadata: Map<String, Any> = emptyMap()
        ): Meter {
            val meter = Meter(
                tenantId = tenantId,
                name = name,
                description = description,
                unit = unit,
                aggregationType = aggregationType,
                pricingModel = pricingModel,
                metadata = metadata
            )
            meter.validate()
            return meter
        }
    }
}

/**
 * How to aggregate usage events
 */
enum class AggregationType {
    /**
     * Sum all event amounts
     */
    SUM,

    /**
     * Count number of events
     */
    COUNT,

    /**
     * Take maximum event amount
     */
    MAX,

    /**
     * Calculate average event amount
     */
    AVERAGE,

    /**
     * Use latest value (gauge)
     */
    LATEST
}

/**
 * Base class for pricing models
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = PricingModel.Flat::class, name = "flat"),
    JsonSubTypes.Type(value = PricingModel.Tiered::class, name = "tiered"),
    JsonSubTypes.Type(value = PricingModel.Volume::class, name = "volume"),
    JsonSubTypes.Type(value = PricingModel.Package::class, name = "package")
)
sealed class PricingModel {

    /**
     * Flat rate pricing: fixed price per unit
     * Example: $0.01 per API call
     */
    data class Flat(
        val unitPrice: BigDecimal
    ) : PricingModel() {
        init {
            require(unitPrice >= BigDecimal.ZERO) { "Unit price cannot be negative" }
        }

        fun calculateCost(quantity: BigDecimal): BigDecimal =
            quantity * unitPrice
    }

    /**
     * Tiered pricing: different prices at different volume levels
     * Example:
     * - 0-1000: $0.10 each
     * - 1001-10000: $0.08 each
     * - 10001+: $0.05 each
     */
    data class Tiered(
        val tiers: List<PricingTier>
    ) : PricingModel() {
        init {
            require(tiers.isNotEmpty()) { "Must have at least one tier" }
            require(tiers == tiers.sortedBy { it.upTo }) { "Tiers must be sorted by upTo" }
        }

        fun calculateCost(quantity: BigDecimal): BigDecimal {
            var remaining = quantity
            var totalCost = BigDecimal.ZERO
            var previousUpTo = BigDecimal.ZERO

            for (tier in tiers.sortedBy { it.upTo }) {
                if (remaining <= BigDecimal.ZERO) break

                val tierQuantity = minOf(
                    remaining,
                    tier.upTo - previousUpTo
                )

                totalCost += tierQuantity * tier.unitPrice
                remaining -= tierQuantity
                previousUpTo = tier.upTo
            }

            // Handle overflow beyond last tier
            if (remaining > BigDecimal.ZERO && tiers.isNotEmpty()) {
                totalCost += remaining * tiers.last().unitPrice
            }

            return totalCost
        }
    }

    /**
     * Volume pricing: price depends on total volume (retroactive)
     * Example:
     * - 0-1000: $0.10 each (all units)
     * - 1001-10000: $0.08 each (all units)
     * - 10001+: $0.05 each (all units)
     */
    data class Volume(
        val breakpoints: Map<BigDecimal, BigDecimal> // volume -> unit price
    ) : PricingModel() {
        init {
            require(breakpoints.isNotEmpty()) { "Must have at least one breakpoint" }
        }

        fun calculateCost(quantity: BigDecimal): BigDecimal {
            val applicablePrice = breakpoints
                .entries
                .sortedByDescending { it.key }
                .firstOrNull { quantity >= it.key }
                ?.value
                ?: breakpoints.values.first()

            return quantity * applicablePrice
        }
    }

    /**
     * Package pricing: fixed price for a bundle
     * Example: $100 for 10,000 units, then $0.01 per additional unit
     */
    data class Package(
        val packageSize: BigDecimal,
        val packagePrice: BigDecimal,
        val overageUnitPrice: BigDecimal
    ) : PricingModel() {
        init {
            require(packageSize > BigDecimal.ZERO) { "Package size must be positive" }
            require(packagePrice >= BigDecimal.ZERO) { "Package price cannot be negative" }
            require(overageUnitPrice >= BigDecimal.ZERO) { "Overage price cannot be negative" }
        }

        fun calculateCost(quantity: BigDecimal): BigDecimal {
            val packages = (quantity / packageSize).setScale(0, java.math.RoundingMode.UP)
            val includedQuantity = packages * packageSize
            val overage = (quantity - includedQuantity).max(BigDecimal.ZERO)

            return (packages * packagePrice) + (overage * overageUnitPrice)
        }
    }
}

/**
 * Tier definition for tiered pricing
 */
data class PricingTier(
    /**
     * Upper limit for this tier (exclusive)
     */
    val upTo: BigDecimal,

    /**
     * Price per unit in this tier
     */
    val unitPrice: BigDecimal
) {
    init {
        require(upTo > BigDecimal.ZERO) { "Tier upper limit must be positive" }
        require(unitPrice >= BigDecimal.ZERO) { "Unit price cannot be negative" }
    }
}

/**
 * Result of a pricing calculation
 */
data class PriceCalculation(
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val totalAmount: BigDecimal,
    val description: String,
    val breakdown: Map<String, BigDecimal> = emptyMap()
)
