package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ServiceCouponMapper;
import com.transit.model.ServiceCoupon;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ServiceCouponAdminService {
    private final ServiceCouponMapper couponMapper;
    private final JdbcTemplate jdbcTemplate;

    public List<ServiceCoupon> list() {
        List<ServiceCoupon> coupons = couponMapper.selectList(new LambdaQueryWrapper<ServiceCoupon>().orderByDesc(ServiceCoupon::getCreatedAt));
        coupons.forEach(c -> c.setServiceIds(jdbcTemplate.queryForList("SELECT service_id FROM service_coupon_services WHERE coupon_id=? ORDER BY service_id", Long.class, c.getId())));
        return coupons;
    }

    @Transactional
    public ServiceCoupon save(Long id, ServiceCoupon request) {
        if (request == null) throw badRequest("Coupon body is required");
        String code = normalizeCode(request.getCode());
        long discount = request.getDiscountCents() == null ? -1 : request.getDiscountCents();
        int remaining = request.getRemainingUses() == null ? -1 : request.getRemainingUses();
        if (discount < 0) throw badRequest("discountCents must be non-negative");
        if (remaining < 0) throw badRequest("remainingUses must be non-negative");
        if (request.getServiceIds() == null || request.getServiceIds().isEmpty()) throw badRequest("At least one applicable service is required");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ServiceCoupon coupon = id == null ? new ServiceCoupon() : couponMapper.selectById(id);
        if (coupon == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found");
        coupon.setCode(code); coupon.setDiscountCents(discount); coupon.setRemainingUses(remaining);
        if (coupon.getReservedUses() == null) coupon.setReservedUses(0);
        coupon.setEnabled(request.getEnabled() == null || request.getEnabled()); coupon.setUpdatedAt(now);
        if (id == null) { coupon.setCreatedAt(now); couponMapper.insert(coupon); } else couponMapper.updateById(coupon);
        jdbcTemplate.update("DELETE FROM service_coupon_services WHERE coupon_id=?", coupon.getId());
        for (Long serviceId : request.getServiceIds().stream().distinct().toList()) {
            Integer exists = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM other_services WHERE id=?", Integer.class, serviceId);
            if (exists == null || exists == 0) throw badRequest("Applicable service does not exist: " + serviceId);
            jdbcTemplate.update("INSERT INTO service_coupon_services(coupon_id, service_id) VALUES (?, ?)", coupon.getId(), serviceId);
        }
        coupon.setServiceIds(request.getServiceIds().stream().distinct().toList());
        return coupon;
    }

    public void delete(Long id) {
        ServiceCoupon coupon = couponMapper.selectById(id);
        if (coupon == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found");
        coupon.setEnabled(false); coupon.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC)); couponMapper.updateById(coupon);
    }

    private String normalizeCode(String value) {
        if (value == null || value.isBlank()) throw badRequest("code is required");
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]{3,80}")) throw badRequest("code must contain 3-80 letters, digits, underscores, or hyphens");
        return code;
    }

    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
