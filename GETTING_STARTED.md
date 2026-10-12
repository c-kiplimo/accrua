# Getting Started with Accrua

This guide will help you get Accrua up and running on your local machine.

## Prerequisites

Ensure you have the following installed:

- **Java 21** or higher ([Download](https://adoptium.net/))
- **Maven 3.8+** ([Download](https://maven.apache.org/download.cgi))
- **Docker Desktop** ([Download](https://www.docker.com/products/docker-desktop))
- **Git** ([Download](https://git-scm.com/downloads))

## Quick Start (5 minutes)

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/accrua.git
cd accrua
```

### 2. Start Infrastructure Services

Start PostgreSQL, Kafka, and Redis using Docker Compose:

```bash
docker-compose up -d postgres kafka redis
```

Wait for services to be healthy (about 30 seconds):

```bash
docker-compose ps
```

You should see all services with status "Up" and "healthy".

### 3. Run Database Migrations

Create the database schema:

```bash
mvn flyway:migrate
```

### 4. Build the Application

```bash
mvn clean package -DskipTests
```

### 5. Run the Application

```bash
java -jar target/accrua-metering-1.0.0-SNAPSHOT.jar
```

Or using Maven:

```bash
mvn spring-boot:run
```

### 6. Verify It's Working

Check health endpoint:

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{
  "status": "UP"
}
```

## Your First API Call

### 1. Create a Meter

First, you need a meter to define what you're measuring:

```bash
curl -X POST http://localhost:8080/api/v1/meters \
  -H "Content-Type: application/json" \
  -d '{
    "name": "api_calls",
    "description": "API call consumption",
    "unit": "requests",
    "aggregation_type": "SUM",
    "pricing_model": {
      "type": "flat",
      "unit_price": 0.01
    }
  }'
```

### 2. Create an Allocation

Create a resource pool for a sponsor:

```bash
curl -X POST http://localhost:8080/api/v1/allocations \
  -H "Content-Type: application/json" \
  -d '{
    "sponsor_id": "sponsor-123",
    "provider_id": "provider-456",
    "meter_id": "<METER_ID_FROM_STEP_1>",
    "total_amount": 10000,
    "expires_at": "2027-01-01T00:00:00Z"
  }'
```

### 3. Ingest a Usage Event

Record consumption:

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "allocation_id": "<ALLOCATION_ID_FROM_STEP_2>",
    "end_user_id": "user-789",
    "meter_id": "<METER_ID_FROM_STEP_1>",
    "amount": 100,
    "occurred_at": "2026-10-12T10:30:00Z",
    "event_type": "api_call"
  }'
```

Expected response:
```json
{
  "event_id": "...",
  "status": "ACCEPTED",
  "timestamp": "2026-10-12T10:30:00.123Z"
}
```

### 4. Check Balance

Query the current balance:

```bash
curl http://localhost:8080/api/v1/allocations/<ALLOCATION_ID>/balance
```

Expected response:
```json
{
  "allocation_id": "...",
  "total_amount": 10000.00,
  "used_amount": 100.00,
  "remaining_amount": 9900.00,
  "utilization_rate": 0.01,
  "status": "active"
}
```

## Development Workflow

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=AllocationTest

# Run with coverage
mvn clean verify
```

### Code Formatting

```bash
# Format code (when ktlint is configured)
mvn ktlint:format

# Check formatting
mvn ktlint:check
```

### Hot Reload

When running with `mvn spring-boot:run`, changes to code will automatically restart the application (Spring Boot DevTools).

### Monitoring

Access the monitoring tools:

- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)
- **Jaeger**: http://localhost:16686
- **Kafka UI**: http://localhost:8090
- **pgAdmin**: http://localhost:5050

## Docker Development

### Build Docker Image

```bash
docker build -t accrua/metering:latest .
```

### Run with Docker Compose

Uncomment the `app` service in `docker-compose.yml`, then:

```bash
docker-compose up
```

## Project Structure

```
accrua/
├── src/main/kotlin/com/accrua/
│   ├── domain/model/           # Domain entities
│   ├── repository/             # Data access layer
│   ├── service/                # Business logic
│   ├── api/
│   │   ├── controller/         # REST endpoints
│   │   └── dto/                # Request/response DTOs
│   └── config/                 # Configuration classes
├── src/main/resources/
│   ├── application.yml         # Application config
│   └── db/migration/           # Flyway migrations
├── src/test/                   # Tests
├── monitoring/                 # Prometheus/Grafana configs
├── docker-compose.yml          # Local infrastructure
├── Dockerfile                  # Application container
└── pom.xml                     # Maven configuration
```

## Common Tasks

### Reset Database

```bash
# Stop services
docker-compose down -v

# Start fresh
docker-compose up -d postgres
mvn flyway:clean
mvn flyway:migrate
```

### View Logs

```bash
# Application logs
tail -f logs/accrua.log

# Docker logs
docker-compose logs -f app
```

### Create Kafka Topics Manually

```bash
docker exec -it accrua-kafka kafka-topics \
  --create \
  --bootstrap-server localhost:9092 \
  --topic usage-events \
  --partitions 24 \
  --replication-factor 1
```

### Connect to Database

```bash
docker exec -it accrua-postgres psql -U accrua -d accrua
```

## Troubleshooting

### Port Already in Use

If you see "Address already in use" errors:

```bash
# Find process using port 8080
lsof -i :8080  # macOS/Linux
netstat -ano | findstr :8080  # Windows

# Kill the process or change the port in application.yml
```

### Kafka Connection Issues

```bash
# Check Kafka is running
docker-compose ps kafka

# View Kafka logs
docker-compose logs kafka

# Restart Kafka
docker-compose restart kafka
```

### Database Migration Fails

```bash
# Check PostgreSQL is running
docker-compose ps postgres

# View PostgreSQL logs
docker-compose logs postgres

# Manually run migration
mvn flyway:migrate -X
```

## Next Steps

- Read the [Solution Architecture](SOLUTION_ARCHITECTURE.md) for detailed design
- Explore the [API Documentation](docs/api.md)
- Check out [Examples](examples/) for common use cases
- Join the [Discussions](https://github.com/yourusername/accrua/discussions)

## Getting Help

- **Issues**: [GitHub Issues](https://github.com/yourusername/accrua/issues)
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/accrua/discussions)
- **Email**: your.email@example.com

## What's Next?

Now that you have Accrua running, try:

1. **Load Testing**: Use K6 to simulate high event volumes
2. **Custom Pricing**: Implement tiered or volume pricing models
3. **Time Decay**: Configure decay policies for resource expiration
4. **Invoicing**: Generate invoices for consumption
5. **Analytics**: Build dashboards in Grafana

Happy coding! 🚀
