package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.mapper.WalletRechargeOrderMapper;
import com.transit.model.PaymentIntent;
import com.transit.model.ServiceOrder;
import com.transit.model.WalletRechargeOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentBusinessSettlementService {
    public static final String SERVICE_ORDER = "SERVICE_ORDER";
    public static final String WALLET_RECHARGE = "WALLET_RECHARGE";

    private final ServiceOrderMapper orderMapper;
    private final WalletRechargeOrderMapper rechargeOrderMapper;
    private final ServiceCommerceService serviceCommerceService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void settle(PaymentIntent intent) {
        switch (intent.getBusinessType()) {
            case SERVICE_ORDER -> settleServiceOrder(intent);
            case WALLET_RECHARGE -> settleRecharge(intent);
            default -> throw conflict("Unsupported payment business type");
        }
    }

    @Transactional
    public void prepareRefund(PaymentIntent intent) {
        switch (intent.getBusinessType()) {
            case SERVICE_ORDER -> prepareServiceRefund(intent);
            case WALLET_RECHARGE -> prepareRechargeRefund(intent);
            default -> throw conflict("Unsupported payment business type");
        }
    }

    @Transactional
    public void completeRefund(PaymentIntent intent) {
        LocalDateTime now = now();
        if (SERVICE_ORDER.equals(intent.getBusinessType())) {
            ServiceOrder order = requireOrder(intent);
            serviceCommerceService.releasePaidResourcesForRefund(order);
            order.setStatus("REFUNDED");
            order.setFulfillmentStatus("REFUNDED");
            order.setFulfillmentNote("Payment refunded: " + intent.getRefundReason());
            order.setUpdatedAt(now);
            orderMapper.updateById(order);
            return;
        }
        WalletRechargeOrder order = requireRecharge(intent);
        order.setStatus("REFUNDED"); order.setRefundedAt(now); order.setUpdatedAt(now);
        rechargeOrderMapper.updateById(order);
        jdbcTemplate.update("UPDATE wallet_transactions SET type='REFUND_RECHARGE' WHERE reference_type=? AND reference_id=?", refundReference(intent), order.getId());
    }

    @Transactional
    public void compensateRefund(PaymentIntent intent) {
        if (SERVICE_ORDER.equals(intent.getBusinessType())) {
            ServiceOrder order = requireOrder(intent);
            if ("REFUND_PENDING".equals(order.getStatus())) {
                order.setStatus("PAID"); order.setFulfillmentStatus(intent.getInternalState()==null?"PENDING":intent.getInternalState()); order.setUpdatedAt(now());
                orderMapper.updateById(order);
            }
            return;
        }
        WalletRechargeOrder order = requireRecharge(intent);
        if (!"REFUND_PENDING".equals(order.getStatus())) return;
        long amount = order.getTotalCreditUnits();
        jdbcTemplate.update("UPDATE users SET balance=balance+? WHERE id=?", amount, order.getUserId());
        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, order.getUserId());
        jdbcTemplate.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark,reference_type,reference_id,created_at) VALUES (?, 'REFUND_RELEASE', ?, ?, 'anyipay', ?, ?, ?, ?)",
                order.getUserId(), amount, balance == null ? 0 : balance,
                "Refund failed; held recharge credit restored", refundReference(intent).replace("RFD_HOLD_","RFD_REL_"), order.getId(), now());
        order.setStatus("PAID"); order.setUpdatedAt(now()); rechargeOrderMapper.updateById(order);
    }

    private void settleServiceOrder(PaymentIntent intent) {
        ServiceOrder order = requireOrder(intent);
        String current = normalize(order.getStatus());
        if (List.of("PAID", "FULFILLED").contains(current)) return;
        if (!List.of("PENDING", "CONFIRMED", "EXPIRED").contains(current)) throw conflict("Service order cannot accept payment");
        LocalDateTime now = now();
        order.setStatus("PAID"); order.setPaymentProvider("ANYIPAY");
        order.setProviderTradeNo(intent.getProviderTradeNo()); order.setPaymentReference(intent.getProviderTradeNo());
        order.setPaymentType(intent.getPaymentType()); order.setPaymentUrl(intent.getPaymentUrl());
        order.setPaidAt(now); order.setUpdatedAt(now);
        int updated = orderMapper.update(order, new LambdaUpdateWrapper<ServiceOrder>()
                .eq(ServiceOrder::getId, order.getId()).eq(ServiceOrder::getStatus, current));
        if (updated != 1) return;
        if (order.getServiceId() != null) serviceCommerceService.settlePaid(order);
        else if (ServiceCommerceService.MANUAL.equals(order.getFulfillmentMode())) {
            order.setFulfillmentStatus("PENDING");
            order.setFulfillmentNote("Payment verified; awaiting administrator procurement and delivery.");
            orderMapper.updateById(order);
        }
    }

    private void settleRecharge(PaymentIntent intent) {
        WalletRechargeOrder order = requireRecharge(intent);
        if ("PAID".equals(order.getStatus())) return;
        if (!List.of("PENDING", "EXPIRED").contains(order.getStatus())) throw conflict("Recharge order cannot accept payment");
        int claimed = rechargeOrderMapper.update(null, new LambdaUpdateWrapper<WalletRechargeOrder>()
                .set(WalletRechargeOrder::getStatus, "CREDITING").set(WalletRechargeOrder::getUpdatedAt, now())
                .eq(WalletRechargeOrder::getId, order.getId()).in(WalletRechargeOrder::getStatus, "PENDING", "EXPIRED"));
        if (claimed != 1) return;
        credit(order, "RECHARGE", order.getBaseCreditUnits(), "RECHARGE_BASE");
        if (order.getBonusCreditUnits() > 0) credit(order, "GIFT", order.getBonusCreditUnits(), "RECHARGE_GIFT");
        order.setStatus("PAID"); order.setPaidAt(now()); order.setUpdatedAt(now()); rechargeOrderMapper.updateById(order);
    }

    private void credit(WalletRechargeOrder order, String type, long amount, String referenceType) {
        if (amount <= 0) return;
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_transactions WHERE reference_type=? AND reference_id=?", Integer.class, referenceType, order.getId());
        if (existing != null && existing > 0) return;
        jdbcTemplate.update("UPDATE users SET balance=balance+? WHERE id=?", amount, order.getUserId());
        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, order.getUserId());
        jdbcTemplate.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark,reference_type,reference_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                order.getUserId(), type, amount, balance == null ? 0 : balance, "anyipay",
                "Recharge order " + order.getOrderNo(), referenceType, order.getId(), now());
    }

    private void prepareServiceRefund(PaymentIntent intent) {
        ServiceOrder order = requireOrder(intent);
        if (!("PAID".equals(order.getStatus()) || "REFUND_PENDING".equals(order.getStatus())) || "COMPLETED".equals(order.getFulfillmentStatus())) {
            throw conflict("Only a paid, unfulfilled service order can be refunded");
        }
        intent.setInternalState(order.getFulfillmentStatus());
        order.setStatus("REFUND_PENDING"); order.setUpdatedAt(now()); orderMapper.updateById(order);
    }

    private void prepareRechargeRefund(PaymentIntent intent) {
        WalletRechargeOrder order = requireRecharge(intent);
        if (!"PAID".equals(order.getStatus())) throw conflict("Only a paid recharge can be refunded");
        long amount = order.getTotalCreditUnits();
        int debited = jdbcTemplate.update("UPDATE users SET balance=balance-? WHERE id=? AND balance>=?", amount, order.getUserId(), amount);
        if (debited != 1) throw conflict("User balance is insufficient to reverse this recharge and its bonus");
        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, order.getUserId());
        jdbcTemplate.update("INSERT INTO wallet_transactions(user_id,type,amount,balance_after,channel,remark,reference_type,reference_id,created_at) VALUES (?, 'REFUND_HOLD', ?, ?, 'anyipay', ?, ?, ?, ?)",
                order.getUserId(), -amount, balance == null ? 0 : balance,
                "Held for full recharge refund", refundReference(intent), order.getId(), now());
        order.setStatus("REFUND_PENDING"); order.setUpdatedAt(now()); rechargeOrderMapper.updateById(order);
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
    private String refundReference(PaymentIntent intent) {
        String value=intent.getRefundNo()==null?"UNKNOWN":intent.getRefundNo().replaceAll("[^A-Za-z0-9]","");
        return "RFD_HOLD_"+value.substring(Math.max(0,value.length()-20));
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
