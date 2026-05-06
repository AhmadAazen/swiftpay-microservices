package com.ledger.service;
import com.ledger.dto.PaymentHistory;
import com.ledger.dto.PaymentInitiatedEvent;
import com.ledger.dto.PaymentResultEvent;
import com.ledger.entity.*;
import com.ledger.enums.PaymentStatus;
import com.ledger.producer.PaymentResultProducer;
import com.ledger.repository.*;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PaymentResultProducer paymentResultProducer;

    @Transactional
    public void processPayment(PaymentInitiatedEvent event) {

        Account sender =
                accountRepository.findByIdForUpdate(
                                event.getSenderId())
                        .orElseThrow();

        Account receiver =
                accountRepository.findByIdForUpdate(
                                event.getReceiverId())
                        .orElseThrow();

        // FAILURE PATH: insufficient balance
        if (sender.getBalance()
                .compareTo(event.getAmount()) < 0) {

            Payment payment =
                    paymentRepository.findById(
                                    event.getPaymentId())
                            .orElseThrow();

            payment.setStatus(PaymentStatus.FAILED);
            paymentRepository.save(payment);

            PaymentResultEvent result = PaymentResultEvent.builder()
                    .paymentId(payment.getId())
                    .transactionId(payment.getTransactionId())
                    .senderId(payment.getSenderId())
                    .receiverId(payment.getReceiverId())
                    .amount(payment.getAmount())
                    .currency(payment.getCurrency())
                    .status("FAILED")
                    .failureReason("Insufficient balance")
                    .timestamp(Instant.now())
                    .build();

            // publish to payment.failed topic
            paymentResultProducer.publish(result);

            throw new RuntimeException("Insufficient balance");
        }

        // SUCCESS PATH: perform transfer
        sender.setBalance(
                sender.getBalance()
                        .subtract(event.getAmount()));

        receiver.setBalance(
                receiver.getBalance()
                        .add(event.getAmount()));

        accountRepository.save(sender);
        accountRepository.save(receiver);

        LedgerEntry debitEntry =
                LedgerEntry.builder()
                        .paymentId(event.getPaymentId())
                        .accountId(sender.getId())
                        .entryType("DEBIT")
                        .amount(event.getAmount())
                        .build();

        LedgerEntry creditEntry =
                LedgerEntry.builder()
                        .paymentId(event.getPaymentId())
                        .accountId(receiver.getId())
                        .entryType("CREDIT")
                        .amount(event.getAmount())
                        .build();

        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        Payment payment =
                paymentRepository.findById(
                                event.getPaymentId())
                        .orElseThrow();

        payment.setStatus(PaymentStatus.COMPLETED);
        paymentRepository.save(payment);

        PaymentResultEvent result = PaymentResultEvent.builder()
                .paymentId(payment.getId())
                .transactionId(payment.getTransactionId())
                .senderId(payment.getSenderId())
                .receiverId(payment.getReceiverId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status("COMPLETED")
                .failureReason(null)
                .timestamp(Instant.now())
                .build();

        // publish to payment.completed topic
        paymentResultProducer.publish(result);
    }

    public List<PaymentHistory> getTransactionHistory(UUID userId) {

        List<Payment> sentPayments =
                paymentRepository.findBySenderId(userId);

        List<Payment> receivedPayments =
                paymentRepository.findByReceiverId(userId);

        List<Payment> all = new ArrayList<>();
        all.addAll(sentPayments);
        all.addAll(receivedPayments);

        return all.stream()
                .map(this::toHistoryDto)
                // optional: newest first
                .sorted(Comparator.comparing(PaymentHistory::getCreatedAt).reversed())
                .toList();
    }

    private PaymentHistory toHistoryDto(Payment p) {
        return PaymentHistory.builder()
                .paymentId(p.getId())
                .transactionId(p.getTransactionId())
                .senderId(p.getSenderId())
                .receiverId(p.getReceiverId())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .status(p.getStatus())
                .failureReason(p.getFailureReason())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }
}
