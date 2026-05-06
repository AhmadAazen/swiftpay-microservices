package com.ledger.producer;


import com.ledger.dto.PaymentResultEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentResultProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(PaymentResultEvent event) {
        String topic = "COMPLETED".equals(event.getStatus())
                ? "payment.completed"
                : "payment.failed";

        kafkaTemplate.send(
                topic,
                event.getTransactionId().toString(),
                event
        );
    }
}
