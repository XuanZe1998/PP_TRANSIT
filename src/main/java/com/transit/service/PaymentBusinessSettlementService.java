package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.mapper.WalletRechargeOrderMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.ServiceOrder;
import com.transit.model.WalletRechargeOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentBusinessSettlementService {
    public static final String SERVICE_ORDER = "SERVICE_ORDER";
    public static final String WALLET_RECHARGE = "WALLET_RECHARGE";

    private final ServiceOrderMapper orderMapper;
    private final WalletRechargeOrderMapper rechargeOrderMapper;
    private final ServiceCommerceService serviceCommerceService;
    private final JdbcTemplate jdbcTemplate;
    private final WalletBalanceService walletBalanceService;
    private final PlatformTransactionManager transactionManager;

    @Transactional
    public ServiceOrder payServiceOrderFromBalance(ServiceOrder order) {
        String current = normalize(order.getStatus());
        if (List.of("PAID", "FULFILLED").contains(current)) return order;
        if (!List.of("PENDING", "CONFIRMED").contains(current)) {
            throw conflict("订单当前无法支付");
        }
        LocalDateTime paidAt = now();
        order.setStatus("PAID");
        order.setPaymentProvider("BALANCE");
        order.setPaymentReference("BALANCE-" + order.getOrderNo());
        order.setPaidAt(paidAt);
        order.setUpdatedAt(paidAt);
        int updated = orderMapper.update(order, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId())
                .eq(ServiceOrder::getPaymentMethod, "balance")
                .eq(ServiceOrder::getStatus, current)
                .and(expiry -> expiry.isNull(ServiceOrder::getReservationExpiresAt)
                        .or().gt(ServiceOrder::getReservationExpiresAt, paidAt)));
        if (updated != 1) throw conflict("订单已过期或状态已变化，请刷新后查看");

        long amount = Math.multiplyExact(order.getPaymentAmountCents(), 100L);
        long balance = walletBalanceService.debit(order.getUserId(), amount, "余额不足").balance();
        jdbcTemplate.update("""
                INSERT INTO wallet_transactions
                (user_id,type,amount,balance_after,channel,remark,reference_type,reference_id,created_at)
                VALUES (?,'CONSUME',?,?,'balance',?,'SERVICE_ORDER',?,?)
                """, order.getUserId(), -amount, balance, "订阅订单 " + order.getOrderNo(), order.getId(), paidAt);
        enqueueEmail(SERVICE_ORDER, order.getId());
        return serviceCommerceService.settlePaid(order);
    }

    @Transactional
    public void settle(PaymentIntent intent) {
        switch (intent.getBusinessType()) {
            case SERVICE_ORDER -> settleServiceOrder(intent);
            case WALLET_RECHARGE -> settleRecharge(intent);
            default -> throw conflict("Unsupported payment business type");
        }
    }

    private void settleServiceOrder(PaymentIntent intent) {
        ServiceOrder order = requireOrder(intent);
        String current = normalize(order.getStatus());
        if (List.of("PAID", "FULFILLED").contains(current)) return;
        // A provider may confirm funds after the administrator closed checkout.
        if (!List.of("PENDING", "CONFIRMED", "EXPIRED", "CANCELLED").contains(current)) {
            throw conflict("Service order cannot accept payment");
        }
        LocalDateTime paidAt = now();
        order.setStatus("PAID");
        order.setPaymentProvider("MAPAY");
        order.setProviderTradeNo(intent.getProviderTradeNo());
        order.setPaymentReference(intent.getProviderTradeNo());
        order.setPaymentType(intent.getPaymentType());
        order.setPaymentUrl(intent.getPaymentUrl());
        order.setPaidAt(paidAt);
        order.setUpdatedAt(paidAt);
        int updated = orderMapper.update(order, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId()).eq(ServiceOrder::getStatus, current));
        if (updated != 1) throw conflict("Service order changed while settling payment");
        enqueueEmail(SERVICE_ORDER, order.getId());
        try {
            if (order.getServiceId() != null) {
                TransactionTemplate fulfillment = new TransactionTemplate(transactionManager);
                fulfillment.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
                fulfillment.executeWithoutResult(ignored -> serviceCommerceService.settlePaid(order));
            } else if (ServiceCommerceService.MANUAL.equals(order.getFulfillmentMode())) {
                order.setFulfillmentStatus("PENDING");
                order.setFulfillmentNote("Payment verified; awaiting administrator procurement and delivery.");
                orderMapper.updateById(order);
            }
        } catch (RuntimeException fulfillmentFailure) {
            orderMapper.update(null, new LambdaUpdateWrapper<ServiceOrder>()
                    .eq(ServiceOrder::getId, order.getId())
                    .set(ServiceOrder::getFulfillmentStatus, "REVIEW_REQUIRED")
                    .set(ServiceOrder::getFulfillmentNote, "Payment verified, but automatic fulfillment needs administrator review.")
                    .set(ServiceOrder::getUpdatedAt, now()));
            log.warn("Paid service order {} requires fulfillment review: {}",
                    order.getId(), fulfillmentFailure.getMessage());
        }
    }

    private void settleRecharge(PaymentIntent intent) {
        WalletRechargeOrder order = requireRecharge(intent);
        if ("PAID".equals(order.getStatus())) return;
        if (!List.of("PENDING", "EXPIRED").contains(order.getStatus())) {
            throw conflict("Recharge order cannot accept payment");
        }
        int claimed = rechargeOrderMapper.update(null, new LambdaUpdateWrapper<WalletRechargeOrder>()
                .set(WalletRechargeOrder::getStatus, "CREDITING")
                .set(WalletRechargeOrder::getUpdatedAt, now())
                .eq(WalletRechargeOrder::getId, order.getId())
                .in(WalletRechargeOrder::getStatus, "PENDING", "EXPIRED"));
        if (claimed != 1) return;
        credit(order, "RECHARGE", order.getBaseCreditUnits(), "RECHARGE_BASE");
        if (order.getBonusCreditUnits() > 0) {
            credit(order, "GIFT", order.getBonusCreditUnits(), "RECHARGE_GIFT");
        }
        order.setStatus("PAID");
        order.setPaidAt(now());
        order.setUpdatedAt(now());
        rechargeOrderMapper.updateById(order);
        enqueueEmail(WALLET_RECHARGE, order.getId());
    }

    void enqueueEmail(String type, Long id) {
        // This insert commits atomically with the payment; repeated callbacks return before reaching it.
        jdbcTemplate.update("INSERT INTO payment_email_outbox(business_type,business_id,next_attempt_at,created_at) VALUES (?,?,?,?)",
                type, id, now(), now());
    }

    private void credit(WalletRechargeOrder order, String type, long amount, String referenceType) {
        if (amount <= 0) return;
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_transactions WHERE reference_type=? AND reference_id=?",
                Integer.class, referenceType, order.getId());
        if (existing != null && existing > 0) return;
        long balance = walletBalanceService.credit(order.getUserId(), amount).balance();
        jdbcTemplate.update("""
                INSERT INTO wallet_transactions
                (user_id,type,amount,balance_after,channel,remark,reference_type,reference_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, order.getUserId(), type, amount, balance, "mapay",
                "Recharge order " + order.getOrderNo(), referenceType, order.getId(), now());
    }

    private ServiceOrder requireOrder(PaymentIntent intent) {
        ServiceOrder order = orderMapper.selectById(intent.getBusinessId());
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service order not found");
        return order;
    }

    private WalletRechargeOrder requireRecharge(PaymentIntent intent) {
        WalletRechargeOrder order = rechargeOrderMapper.selectById(intent.getBusinessId());
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recharge order not found");
        return order;
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
