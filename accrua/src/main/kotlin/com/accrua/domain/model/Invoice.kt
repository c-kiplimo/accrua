package com.accrua.domain.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Invoice represents a settlement document for consumed resources.
 *
 * This implements the invoice-based settlement model:
 * - Capture events throughout the billing period
 * - Generate invoice at period end
 * - Bill sponsor for actual consumption
 *
 * Alternative: Real-time debit (prepaid model)
 */
@Table("invoices")
data class Invoice(
    @Id
    val id: UUID = UUID.randomUUID(),

    /**
     * The sponsor being billed
     */
    val sponsorId: String,

    /**
     * Billing period date
     */
    val billingDate: LocalDate,

    /**
     * Payment due date
     */
    val dueDate: LocalDate,

    /**
     * Total amount due
     */
    val totalAmount: BigDecimal,

    /**
     * Current status
     */
    val status: InvoiceStatus = InvoiceStatus.PENDING,

    /**
     * Line items (stored as JSONB)
     */
    val lineItems: List<InvoiceLineItem>,

    /**
     * When invoice was created
     */
    val createdAt: Instant = Instant.now(),

    /**
     * When invoice was sent to sponsor
     */
    val sentAt: Instant? = null,

    /**
     * When invoice was paid
     */
    val paidAt: Instant? = null,

    /**
     * Payment reference
     */
    val paymentReference: String? = null,

    /**
     * Additional metadata
     */
    val metadata: Map<String, Any> = emptyMap()
) {
    val isPending: Boolean
        get() = status == InvoiceStatus.PENDING

    val isPaid: Boolean
        get() = status == InvoiceStatus.PAID

    val isOverdue: Boolean
        get() = status == InvoiceStatus.OVERDUE

    val daysSinceCreated: Long
        get() = java.time.Duration.between(createdAt, Instant.now()).toDays()

    val daysUntilDue: Long
        get() = java.time.Duration.between(
            Instant.now(),
            dueDate.atStartOfDay().atZone(java.time.ZoneOffset.UTC).toInstant()
        ).toDays()

    /**
     * Mark invoice as sent
     */
    fun markSent(now: Instant = Instant.now()): Invoice =
        copy(
            status = InvoiceStatus.SENT,
            sentAt = now
        )

    /**
     * Mark invoice as paid
     */
    fun markPaid(paymentReference: String, now: Instant = Instant.now()): Invoice =
        copy(
            status = InvoiceStatus.PAID,
            paidAt = now,
            paymentReference = paymentReference
        )

    /**
     * Mark invoice as overdue
     */
    fun markOverdue(): Invoice =
        copy(status = InvoiceStatus.OVERDUE)

    /**
     * Cancel invoice
     */
    fun cancel(): Invoice =
        copy(status = InvoiceStatus.CANCELLED)

    /**
     * Validate invoice
     */
    fun validate() {
        require(sponsorId.isNotBlank()) { "Sponsor ID cannot be blank" }
        require(totalAmount >= BigDecimal.ZERO) { "Total amount cannot be negative" }
        require(lineItems.isNotEmpty()) { "Invoice must have at least one line item" }
        require(dueDate.isAfter(billingDate) || dueDate == billingDate) {
            "Due date must be on or after billing date"
        }

        val calculatedTotal = lineItems.sumOf { it.totalAmount }
        require(calculatedTotal == totalAmount) {
            "Total amount ($totalAmount) must equal sum of line items ($calculatedTotal)"
        }
    }

    companion object {
        /**
         * Create a new invoice
         */
        fun create(
            sponsorId: String,
            billingDate: LocalDate,
            dueDate: LocalDate,
            lineItems: List<InvoiceLineItem>,
            metadata: Map<String, Any> = emptyMap()
        ): Invoice {
            val totalAmount = lineItems.sumOf { it.totalAmount }

            val invoice = Invoice(
                sponsorId = sponsorId,
                billingDate = billingDate,
                dueDate = dueDate,
                totalAmount = totalAmount,
                lineItems = lineItems,
                metadata = metadata
            )

            invoice.validate()
            return invoice
        }

        /**
         * Create invoice with default due date (30 days)
         */
        fun create(
            sponsorId: String,
            billingDate: LocalDate,
            lineItems: List<InvoiceLineItem>,
            dueDays: Long = 30,
            metadata: Map<String, Any> = emptyMap()
        ): Invoice = create(
            sponsorId = sponsorId,
            billingDate = billingDate,
            dueDate = billingDate.plusDays(dueDays),
            lineItems = lineItems,
            metadata = metadata
        )
    }
}

/**
 * Invoice line item
 */
data class InvoiceLineItem(
    val meterId: UUID,
    val meterName: String,
    val description: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val totalAmount: BigDecimal,
    val metadata: Map<String, Any> = emptyMap()
) {
    init {
        require(quantity >= BigDecimal.ZERO) { "Quantity cannot be negative" }
        require(unitPrice >= BigDecimal.ZERO) { "Unit price cannot be negative" }
        require(totalAmount >= BigDecimal.ZERO) { "Total amount cannot be negative" }
    }

    companion object {
        fun create(
            meterId: UUID,
            meterName: String,
            description: String,
            quantity: BigDecimal,
            unitPrice: BigDecimal,
            metadata: Map<String, Any> = emptyMap()
        ): InvoiceLineItem = InvoiceLineItem(
            meterId = meterId,
            meterName = meterName,
            description = description,
            quantity = quantity,
            unitPrice = unitPrice,
            totalAmount = quantity * unitPrice,
            metadata = metadata
        )
    }
}

/**
 * Invoice lifecycle status
 */
enum class InvoiceStatus {
    /**
     * Invoice created but not yet sent
     */
    PENDING,

    /**
     * Invoice sent to sponsor
     */
    SENT,

    /**
     * Invoice paid
     */
    PAID,

    /**
     * Invoice past due date
     */
    OVERDUE,

    /**
     * Invoice cancelled
     */
    CANCELLED,

    /**
     * Invoice disputed by sponsor
     */
    DISPUTED
}

/**
 * Invoice summary for reporting
 */
data class InvoiceSummary(
    val totalInvoices: Int,
    val totalAmount: BigDecimal,
    val paidAmount: BigDecimal,
    val overdueAmount: BigDecimal,
    val pendingAmount: BigDecimal,
    val byStatus: Map<InvoiceStatus, Int>,
    val averageAmount: BigDecimal,
    val oldestUnpaid: Invoice?
)
