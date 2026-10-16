package com.accrua

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Main application class for Accrua - Usage-Based Billing & Metering Infrastructure
 *
 * This application demonstrates senior-level backend engineering practices:
 * - Event-driven architecture with Kafka
 * - Reactive programming with Spring WebFlux and R2DBC
 * - Domain-Driven Design with clear bounded contexts
 * - CQRS pattern for read/write separation
 * - Time-aware operations (decay, expiration)
 * - Multi-tenancy with row-level security
 * - Comprehensive observability (metrics, tracing, logging)
 */
@SpringBootApplication
@EnableR2dbcRepositories
@EnableKafka
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan("com.accrua.config")
class AccruaApplication

fun main(args: Array<String>) {
    runApplication<AccruaApplication>(*args)
}
