package com.gateway.kafka;
import com.gateway.dto.PaymentInitiatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendPaymentInitiated(PaymentInitiatedEvent event) {

        kafkaTemplate.send(
                "payment.initiated",
                event.getTransactionId(),
                event
        );
    }
}
