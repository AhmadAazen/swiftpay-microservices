# SwiftPay – Real-Time Payment Ledger

SwiftPay is a real-time, event-driven payment ledger built with Spring Boot, PostgreSQL, Kafka, and Redis. It demonstrates a production-style fintech flow for peer-to-peer money transfers with strong consistency and clear separation of concerns.

## High-Level Architecture

- **Gateway Service (transaction-gateway)**
  - Exposes REST API for clients (`/v1/payments`).
  - Performs request validation and idempotency using Redis.
  - Persists payments in PostgreSQL with `PENDING` status.
  - Publishes `PaymentInitiatedEvent` to Kafka topic `payment.initiated`.

- **Ledger Service (ledger-service)**
  - Consumes `payment.initiated` events from Kafka.
  - Performs atomic debit/credit within a DB transaction using pessimistic locking.
  - Inserts ledger entries for audit trail.
  - Updates payment status to `COMPLETED` or `FAILED`.
  - Publishes result events (`payment.completed` / `payment.failed`) for downstream consumers.
  - Exposes read API `/v1/ledger/history/{userId}` for transaction history.

- **Infrastructure**
  - **PostgreSQL**: Single shared DB (`swiftpay`) with `accounts`, `payments`, `ledger_entries` tables.
  - **Kafka**: Event backbone with topics `payment.initiated`, `payment.completed`, `payment.failed`.
  - **Redis**: Used for idempotency keys to prevent duplicate payment processing.

## Services

### Gateway Service

**Responsibilities**:
- Accept payment requests from clients.
- Validate input and enforce idempotency.
- Persist initial `PENDING` payment record.
- Publish `payment.initiated` events to Kafka.

**Key Tech**:
- Spring Boot (Web, Data JPA, Kafka, Redis, Validation, Actuator).
- PostgreSQL via Spring Data JPA.
- Redis via Spring Data Redis.
- Kafka via Spring Kafka.

**Port**: `8080`

### Ledger Service

**Responsibilities**:
- Consume `payment.initiated` events.
- Perform atomic balance transfers with pessimistic locking.
- Write ledger entries for each debit/credit.
- Update `payments` status to `COMPLETED` or `FAILED`.
- Publish `payment.completed` / `payment.failed` events.
- Serve transaction history over REST.

**Key Tech**:
- Spring Boot (Web, Data JPA, Kafka, Actuator).
- Same PostgreSQL DB as gateway.

**Port**: `8081`

## Kafka Topics

- `payment.initiated`
  - Produced by: Gateway service.
  - Consumed by: Ledger service.
  - Payload: `PaymentInitiatedEvent` (paymentId, transactionId, senderId, receiverId, amount, currency).

- `payment.completed`
  - Produced by: Ledger service.
  - Consumed by: Downstream systems (e.g., analytics, notifications).
  - Payload: `PaymentResultEvent` with status `COMPLETED`.

- `payment.failed`
  - Produced by: Ledger service.
  - Consumed by: Downstream systems.
  - Payload: `PaymentResultEvent` with status `FAILED` and failure reason.

## Database Schema

All tables live in the same PostgreSQL database `swiftpay`.

### `accounts`

```sql
CREATE TABLE accounts (
    id          UUID PRIMARY KEY,
    balance     NUMERIC(19,2) NOT NULL,
    currency    VARCHAR(10)   NOT NULL,
    created_at  TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
```

- `id`: Account identifier (also used as `userId`).
- `balance`: Current account balance.
- `currency`: Currency code (e.g., `INR`).
- `created_at`: Creation timestamp.

### `payments`

```sql
CREATE TABLE payments (
    id             UUID PRIMARY KEY,
    transaction_id VARCHAR(255) UNIQUE NOT NULL,
    sender_id      UUID NOT NULL,
    receiver_id    UUID NOT NULL,
    amount         NUMERIC(19,2) NOT NULL,
    currency       VARCHAR(10)   NOT NULL,
    status         VARCHAR(30)   NOT NULL,
    failure_reason VARCHAR(255),
    created_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
```

- `status`: One of `PENDING`, `COMPLETED`, `FAILED`.
- `failure_reason`: Reason for failure (e.g., `Insufficient balance`).

### `ledger_entries`

```sql
CREATE TABLE ledger_entries (
    id          UUID PRIMARY KEY,
    payment_id  UUID NOT NULL,
    account_id  UUID NOT NULL,
    entry_type  VARCHAR(10),   -- 'DEBIT' or 'CREDIT'
    amount      NUMERIC(19,2),
    created_at  TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);
```

Each completed payment has exactly two ledger entries: one `DEBIT` for the sender account and one `CREDIT` for the receiver account.

## API Endpoints

### Gateway Service

#### `POST /v1/payments`

**Request body**:

```json
{
  "transactionId": "d0f1f8c2-11f7-4fd7-9f7f-111111111111",
  "senderId": "11111111-1111-1111-1111-111111111111",
  "receiverId": "22222222-2222-2222-2222-222222222222",
  "amount": 500,
  "currency": "INR"
}
```

**Behavior**:
- Validates payload (non-null, amount > 0, sender != receiver, supported currency).
- Checks Redis idempotency key `payment:idempotency:{transactionId}`; if present, returns existing payment response.
- If not duplicate:
  - Creates a `payments` row with `status = PENDING`.
  - Publishes `PaymentInitiatedEvent` to `payment.initiated`.
  - Returns `{ paymentId, status: "PENDING" }`.

### Ledger Service

#### `GET /v1/ledger/history/{userId}`

Returns the transaction history for the given account/user.

**Path parameter**:
- `userId`: UUID of the account.

**Response (example)**:

```json
[
  {
    "paymentId": "caaf29a9-dd01-402d-95da-f5802d2be0b1",
    "transactionId": "d0f1f8c2-11f7-4fd7-9f7f-111111111116",
    "senderId": "11111111-1111-1111-1111-111111111111",
    "receiverId": "22222222-2222-2222-2222-222222222222",
    "amount": 800,
    "currency": "INR",
    "status": "COMPLETED",
    "failureReason": null,
    "createdAt": "2026-05-06T18:45:01.24805",
    "updatedAt": "2026-05-06T18:45:02.119721"
  }
]
```

(Exact shape depends on your `PaymentHistoryDto` mapping.)

## End-to-End Data Flow (Gateway → Kafka → Ledger)

1. **Client → Gateway**
   - Client sends `POST /v1/payments` with transaction details.
   - Gateway validates the request (amount > 0, sender != receiver, etc.).
   - Gateway checks Redis for idempotency key `payment:idempotency:{transactionId}`.

2. **Persist PENDING payment**
   - If not duplicate, gateway writes a row into `payments` with `status = PENDING`.

3. **Publish `payment.initiated`**
   - Gateway builds a `PaymentInitiatedEvent` with `paymentId`, `transactionId`, `senderId`, `receiverId`, `amount`, `currency`.
   - Gateway uses `KafkaTemplate` (JSON serializer) to send this event to topic `payment.initiated`.

4. **Ledger consumes `payment.initiated`**
   - Ledger service has a `@KafkaListener(topics = "payment.initiated", groupId = "ledger-group-…")` that receives `PaymentInitiatedEvent`.
   - The listener calls `LedgerService.processPayment(event)`.

5. **Atomic debit/credit in Ledger**
   - `processPayment` starts a DB transaction.
   - It loads sender and receiver `Account` rows using a pessimistic write lock to prevent double spending.
   - If `sender.balance < amount`:
     - It sets `payments.status = FAILED` and `failure_reason = 'Insufficient balance'`.
     - Publishes a `PaymentResultEvent` with status `FAILED` to `payment.failed`.
     - Transaction rolls back any partial changes.
   - Otherwise:
     - Debits sender and credits receiver.
     - Inserts two `ledger_entries` rows (DEBIT for sender, CREDIT for receiver).
     - Updates `payments.status = COMPLETED` and `updated_at`.
     - Publishes a `PaymentResultEvent` with status `COMPLETED` to `payment.completed`.
   - The transaction is committed only if all DB operations succeed.

6. **Read side / History**
   - Clients can call `GET /v1/ledger/history/{userId}` to view transactions, which aggregates data from `payments` and/or `ledger_entries`.

## Manual End-to-End Test Flow

### Prerequisites

- PostgreSQL, Kafka, and Redis are running (e.g., via Docker).
- Gateway service is running on `http://localhost:8080`.
- Ledger service is running on `http://localhost:8081`.

### 1. Seed Test Accounts

Connect to the `swiftpay` database and ensure two accounts exist:

```sql
INSERT INTO accounts (id, balance, currency, created_at)
VALUES ('11111111-1111-1111-1111-111111111111', 1000.00, 'INR', NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO accounts (id, balance, currency, created_at)
VALUES ('22222222-2222-2222-2222-222222222222', 0.00, 'INR', NOW())
ON CONFLICT (id) DO NOTHING;
```

Verify:

```sql
SELECT id, balance, currency FROM accounts
WHERE id IN ('11111111-1111-1111-1111-111111111111',
             '22222222-2222-2222-2222-222222222222');
```

### 2. Initiate Payment via Gateway

```bash
curl -X POST http://localhost:8080/v1/payments   -H "Content-Type: application/json"   -d '{
        "transactionId": "d0f1f8c2-11f7-4fd7-9f7f-111111111116",
        "senderId": "11111111-1111-1111-1111-111111111111",
        "receiverId": "22222222-2222-2222-2222-222222222222",
        "amount": 800,
        "currency": "INR"
      }'
```

Expected response:

```json
{
  "paymentId": "<some-uuid>",
  "status": "PENDING"
}
```

### 3. Verify Ledger Processing

After a short delay, check the `payments` table:

```sql
SELECT id, transaction_id, status, failure_reason
FROM payments
ORDER BY created_at DESC
LIMIT 5;
```

You should see the new transaction with `status = COMPLETED` (and `failure_reason = NULL` on success).

Check balances:

```sql
SELECT id, balance, currency FROM accounts
WHERE id IN ('11111111-1111-1111-1111-111111111111',
             '22222222-2222-2222-2222-222222222222');
```

Sender should be debited, receiver credited.

Check ledger entries:

```sql
SELECT payment_id, account_id, entry_type, amount, created_at
FROM ledger_entries
ORDER BY created_at DESC
LIMIT 5;
```

You should see one `DEBIT` and one `CREDIT` for the same `payment_id`.

### 4. Verify via Ledger History API

```bash
curl http://localhost:8081/v1/ledger/history/11111111-1111-1111-1111-111111111111
curl http://localhost:8081/v1/ledger/history/22222222-2222-2222-2222-222222222222
```

The responses should reflect the completed payment in each user's history.

## Dockerfiles

Below are example Dockerfiles for both services using a built JAR.

### Gateway Service Dockerfile

Create `gateway-service/Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built jar from the target directory
COPY target/gateway-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### Ledger Service Dockerfile

Create `ledger-service/Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built jar from the target directory
COPY target/ledger-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

## Building and Running with Docker

1. Build JARs for both services:

```bash
mvn clean package -DskipTests
```

2. Build Docker images:

```bash
cd gateway-service
mvn clean package -DskipTests
docker build -t swiftpay-gateway .

cd ../ledger-service
mvn clean package -DskipTests
docker build -t swiftpay-ledger .
```

3. Run containers (example using plain Docker):

```bash
docker run --name swiftpay-gateway -p 8080:8080 --network host swiftpay-gateway

docker run --name swiftpay-ledger -p 8081:8081 --network host swiftpay-ledger
```

(Or include them in a `docker-compose.yml` along with Postgres, Kafka, and Redis.)
