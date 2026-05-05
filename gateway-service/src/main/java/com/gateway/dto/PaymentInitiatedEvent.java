package com.gateway.dto;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class PaymentInitiatedEvent {

    private UUID paymentId;
    private String transactionId;
    private UUID senderId;
    private UUID receiverId;
    private BigDecimal amount;
    private String currency;
}
