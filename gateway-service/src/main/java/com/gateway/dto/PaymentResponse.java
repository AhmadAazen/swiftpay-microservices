package com.gateway.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class PaymentResponse {

    private UUID paymentId;
    private String status;
}
