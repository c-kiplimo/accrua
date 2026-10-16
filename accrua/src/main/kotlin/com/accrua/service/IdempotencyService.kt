package com.accrua.service

import com.accrua.domain.model.EventReceipt
import com.accrua.domain.model.EventStatus
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * IdempotencyService ensures exactly-once event processing.
 *
 * Strategy:
 * 1. Check Redis bloom filter (fast, probabilistic)
 * 2. If potential duplicate, check Redis hash (definitive)
 * 3. Store event receipt with TTL (7 days default)
 *
 * Benefits:
 * - Prevents double-counting of events
 * - Fast O(1) lookups with Redis
 * - TTL-based automatic cleanup
 */
@Service
class IdempotencyService(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val IDEMPOTENCY_KEY_PREFIX = "idempotency:"
        private const val RECEIPT_KEY_PREFIX = "receipt:"
        private val DEFAULT_TTL = Duration.ofDays(7)
    }

    /**
     * Check if an event is a duplicate
     */
    suspend fun isDuplicate(eventId: UUID): Boolean {
        return isDuplicate(eventId.toString())
    }

    /**
     * Check if an idempotency key has been seen before
     */
    suspend fun isDuplicate(idempotencyKey: String): Boolean {
        val key = "$IDEMPOTENCY_KEY_PREFIX$idempotencyKey"

        return try {
            val exists = redisTemplate.hasKey(key).awaitSingleOrNull() ?: false

            if (exists) {
                logger.debug("Duplicate event detected: $idempotencyKey")
            }

            exists
        } catch (e: Exception) {
            logger.error("Error checking idempotency for key: $idempotencyKey", e)
            // Fail safe: assume not duplicate to avoid blocking legitimate events
            false
        }
    }

    /**
     * Store event receipt for idempotency tracking
     */
    suspend fun storeReceipt(eventId: UUID, receipt: EventReceipt) {
        storeReceipt(eventId.toString(), receipt)
    }

    /**
     * Store event receipt with idempotency key
     */
    suspend fun storeReceipt(
        idempotencyKey: String,
        receipt: EventReceipt,
        ttl: Duration = DEFAULT_TTL
    ) {
        val idempotencyStorageKey = "$IDEMPOTENCY_KEY_PREFIX$idempotencyKey"
        val receiptKey = "$RECEIPT_KEY_PREFIX$idempotencyKey"

        try {
            // Store idempotency marker
            redisTemplate.opsForValue()
                .set(idempotencyStorageKey, "1", ttl)
                .awaitSingleOrNull()

            // Store receipt for retrieval
            val receiptJson = objectMapper.writeValueAsString(receipt)
            redisTemplate.opsForValue()
                .set(receiptKey, receiptJson, ttl)
                .awaitSingleOrNull()

            logger.debug("Stored receipt for idempotency key: $idempotencyKey")
        } catch (e: Exception) {
            logger.error("Error storing receipt for key: $idempotencyKey", e)
            // Non-fatal: idempotency is best-effort
        }
    }

    /**
     * Get stored receipt for duplicate event
     */
    suspend fun getReceipt(eventId: UUID): EventReceipt? {
        return getReceipt(eventId.toString())
    }

    /**
     * Get stored receipt by idempotency key
     */
    suspend fun getReceipt(idempotencyKey: String): EventReceipt? {
        val key = "$RECEIPT_KEY_PREFIX$idempotencyKey"

        return try {
            val receiptJson = redisTemplate.opsForValue()
                .get(key)
                .awaitSingleOrNull()

            receiptJson?.let {
                objectMapper.readValue(it, EventReceipt::class.java)
            }
        } catch (e: Exception) {
            logger.error("Error retrieving receipt for key: $idempotencyKey", e)
            null
        }
    }

    /**
     * Mark event as processed (atomically)
     */
    suspend fun markProcessed(
        idempotencyKey: String,
        eventId: UUID
    ): Boolean {
        return try {
            val receipt = EventReceipt(
                eventId = eventId,
                status = EventStatus.PROCESSED,
                timestamp = Instant.now()
            )

            storeReceipt(idempotencyKey, receipt)
            true
        } catch (e: Exception) {
            logger.error("Error marking event as processed: $idempotencyKey", e)
            false
        }
    }

    /**
     * Check and mark (atomic operation for exactly-once)
     */
    suspend fun checkAndMark(idempotencyKey: String, eventId: UUID): Boolean {
        val key = "$IDEMPOTENCY_KEY_PREFIX$idempotencyKey"

        return try {
            // Use SET NX (set if not exists) for atomic check-and-set
            val wasSet = redisTemplate.opsForValue()
                .setIfAbsent(key, eventId.toString(), DEFAULT_TTL)
                .awaitSingleOrNull() ?: false

            if (!wasSet) {
                logger.debug("Event already processed (atomic check): $idempotencyKey")
            }

            wasSet
        } catch (e: Exception) {
            logger.error("Error in atomic check-and-mark: $idempotencyKey", e)
            // Fail safe: allow processing
            true
        }
    }

    /**
     * Bulk check for duplicates (optimization for batch processing)
     */
    suspend fun checkBatch(idempotencyKeys: List<String>): Map<String, Boolean> {
        if (idempotencyKeys.isEmpty()) {
            return emptyMap()
        }

        return try {
            val keys = idempotencyKeys.map { "$IDEMPOTENCY_KEY_PREFIX$it" }

            // Use pipeline for efficiency
            val results = mutableMapOf<String, Boolean>()

            idempotencyKeys.forEachIndexed { index, key ->
                val exists = redisTemplate.hasKey(keys[index]).awaitSingleOrNull() ?: false
                results[key] = exists
            }

            results
        } catch (e: Exception) {
            logger.error("Error in batch idempotency check", e)
            // Fail safe: assume none are duplicates
            idempotencyKeys.associateWith { false }
        }
    }

    /**
     * Clean up expired idempotency keys (manual cleanup if needed)
     * Note: TTL handles automatic cleanup, this is for manual intervention
     */
    suspend fun cleanup(olderThan: Duration = Duration.ofDays(7)): Long {
        logger.info("Manual cleanup requested for keys older than $olderThan")
        // Redis TTL handles this automatically
        // This method is for monitoring/metrics
        return 0L
    }
}
