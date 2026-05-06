package com.ledger.consumer;
import com.ledger.dto.PaymentInitiatedEvent;
import com.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentConsumer {

    private final LedgerService ledgerService;

    @KafkaListener(
            topics = "payment.initiated",
            groupId = "ledger-group-v3"
    )
    public void consume(
            PaymentInitiatedEvent event) {
        log.info("Received PaymentInitiatedEvent: {}", event);
        ledgerService.processPayment(event);
    }
}
