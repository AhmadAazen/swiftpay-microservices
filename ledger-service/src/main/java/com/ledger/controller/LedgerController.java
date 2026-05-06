package com.ledger.controller;
import com.ledger.dto.PaymentHistory;
import com.ledger.entity.Payment;
import com.ledger.service.LedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;

    @GetMapping("/history/{userId}")
    public ResponseEntity<List<PaymentHistory>> getTransactionHistory(
            @PathVariable UUID userId) {

        List<PaymentHistory> history = ledgerService.getTransactionHistory(userId);
        return ResponseEntity.ok(history);
    }
}
