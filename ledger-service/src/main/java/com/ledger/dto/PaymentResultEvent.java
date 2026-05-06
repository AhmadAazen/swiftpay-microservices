package com.ledger.dto;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
@Data
@Builder
public class PaymentResultEvent {
    private UUID paymentId;
    private String transactionId;
    private UUID senderId;
    private UUID receiverId;
    private BigDecimal amount;
    private String currency;
    private String status; // COMPLETED or FAILED
    private String failureReason;
    private Instant timestamp;
    // getters and setters
}
