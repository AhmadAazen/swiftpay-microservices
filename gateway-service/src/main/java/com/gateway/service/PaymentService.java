package com.gateway.service;
import com.gateway.dto.*;
import com.gateway.entity.Payment;
import com.gateway.enums.PaymentStatus;
import com.gateway.kafka.PaymentProducer;
import com.gateway.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProducer paymentProducer;
    private final StringRedisTemplate redisTemplate;

    public PaymentResponse createPayment(PaymentRequest request) {

        String redisKey =
                "payment:idempotency:" + request.getTransactionId();

        Boolean exists =
                redisTemplate.hasKey(redisKey);

        if (Boolean.TRUE.equals(exists)) {
            throw new RuntimeException(
                    "Duplicate transaction request");
        }

        Payment payment = Payment.builder()
                .transactionId(request.getTransactionId())
                .senderId(request.getSenderId())
                .receiverId(request.getReceiverId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .status(PaymentStatus.PENDING)
                .build();

        Payment savedPayment =
                paymentRepository.save(payment);

        redisTemplate.opsForValue().set(
                redisKey,
                savedPayment.getId().toString(),
                Duration.ofHours(24)
        );

        PaymentInitiatedEvent event =
                PaymentInitiatedEvent.builder()
                        .paymentId(savedPayment.getId())
                        .transactionId(savedPayment.getTransactionId())
                        .senderId(savedPayment.getSenderId())
                        .receiverId(savedPayment.getReceiverId())
                        .amount(savedPayment.getAmount())
                        .currency(savedPayment.getCurrency())
                        .build();

        paymentProducer.sendPaymentInitiated(event);

        return PaymentResponse.builder()
                .paymentId(savedPayment.getId())
                .status(savedPayment.getStatus().name())
                .build();
    }
}
