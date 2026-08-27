package com.transit.controller;

import com.transit.service.AnyiPayClient;
import com.transit.service.PaymentIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhooks/anyipay")
public class AnyiPayWebhookController {

    private final AnyiPayClient anyiPayClient;
    private final PaymentIntentService paymentIntentService;

    @RequestMapping(method = {RequestMethod.GET, RequestMethod.POST}, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> notify(@RequestParam MultiValueMap<String, String> parameters) {
        try {
            Map<String, String> verified = anyiPayClient.verifyCallback(parameters);
            paymentIntentService.receiveNotification(verified);
            return ResponseEntity.ok("success");
        } catch (RuntimeException exception) {
            // Never log callback fields: future provider extensions may contain user data.
            log.warn("Rejected AnyiPay callback: {}", exception.getMessage());
            return ResponseEntity.ok("fail");
        }
    }
}
