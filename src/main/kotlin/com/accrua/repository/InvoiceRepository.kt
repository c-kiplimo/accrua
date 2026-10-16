package com.accrua.repository

import com.accrua.domain.model.Invoice
import com.accrua.domain.model.InvoiceStatus
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.*

/**
 * Repository for Invoice entities.
 */
@Repository
interface InvoiceRepository : CoroutineCrudRepository<Invoice, UUID> {

    /**
     * Find invoices by sponsor
     */
    fun findBySponsorIdOrderByBillingDateDesc(sponsorId: String): Flow<Invoice>

    /**
     * Find invoices by status
     */
    fun findByStatusOrderByBillingDateDesc(status: InvoiceStatus): Flow<Invoice>

    /**
     * Find invoices by sponsor and status
     */
    fun findBySponsorIdAndStatus(sponsorId: String, status: InvoiceStatus): Flow<Invoice>

    /**
     * Find invoices in date range
     */
    @Query("""
        SELECT * FROM invoices
        WHERE sponsor_id = :sponsorId
          AND billing_date BETWEEN :startDate AND :endDate
        ORDER BY billing_date DESC
    """)
    fun findBySponsorIdAndDateRange(
        sponsorId: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): Flow<Invoice>

    /**
     * Find overdue invoices
     */
    @Query("""
        SELECT * FROM invoices
        WHERE status IN ('PENDING', 'SENT')
          AND due_date < :today
        ORDER BY due_date ASC
    """)
    fun findOverdue(today: LocalDate = LocalDate.now()): Flow<Invoice>

    /**
     * Find invoices due soon (within days)
     */
    @Query("""
        SELECT * FROM invoices
        WHERE status IN ('PENDING', 'SENT')
          AND due_date BETWEEN :today AND :futureDate
        ORDER BY due_date ASC
    """)
    fun findDueSoon(
        today: LocalDate = LocalDate.now(),
        futureDate: LocalDate = LocalDate.now().plusDays(7)
    ): Flow<Invoice>

    /**
     * Count invoices by status for a sponsor
     */
    suspend fun countBySponsorIdAndStatus(sponsorId: String, status: InvoiceStatus): Long

    /**
     * Sum total amount by sponsor and status
     */
    @Query("""
        SELECT COALESCE(SUM(total_amount), 0) FROM invoices
        WHERE sponsor_id = :sponsorId
          AND status = :status
    """)
    suspend fun sumTotalAmountBySponsorIdAndStatus(
        sponsorId: String,
        status: InvoiceStatus
    ): java.math.BigDecimal

    /**
     * Find latest invoice for a sponsor
     */
    @Query("""
        SELECT * FROM invoices
        WHERE sponsor_id = :sponsorId
        ORDER BY billing_date DESC
        LIMIT 1
    """)
    suspend fun findLatestBySponsorId(sponsorId: String): Invoice?
}
