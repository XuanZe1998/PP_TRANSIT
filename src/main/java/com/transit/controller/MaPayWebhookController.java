package com.transit.controller;

import com.transit.service.MaPayClient;
import com.transit.service.PaymentIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class MaPayWebhookController {
    private final MaPayClient maPayClient;
    private final PaymentIntentService paymentIntentService;

    @RequestMapping(value = "/webhooks/mapay", method = {RequestMethod.GET, RequestMethod.POST},
            produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> notify(@RequestParam MultiValueMap<String, String> parameters) {
        try {
            Map<String, String> verified = maPayClient.verifyCallback(parameters);
            paymentIntentService.receiveNotification(verified);
            return ResponseEntity.ok("success");
        } catch (RuntimeException exception) {
            log.warn("Rejected MaPay callback: {}", exception.getMessage());
            return ResponseEntity.ok("fail");
        }
    }
}
