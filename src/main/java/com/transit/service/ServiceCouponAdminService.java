package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ServiceCouponMapper;
import com.transit.model.ServiceCoupon;
import com.transit.dto.PageResponse;
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
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceCouponAdminService {
    private final ServiceCouponMapper couponMapper;
    private final JdbcTemplate jdbcTemplate;

    public List<ServiceCoupon> list() {
        List<ServiceCoupon> coupons = couponMapper.selectList(new LambdaQueryWrapper<ServiceCoupon>()
                .isNull(ServiceCoupon::getOwnerUserId).orderByDesc(ServiceCoupon::getCreatedAt));
        coupons.forEach(c -> c.setServiceIds(jdbcTemplate.queryForList("SELECT service_id FROM service_coupon_services WHERE coupon_id=? ORDER BY service_id", Long.class, c.getId())));
        return coupons;
    }

    public PageResponse<ServiceCoupon> listPage(int page, int size) {
        validatePage(page, size);
        LambdaQueryWrapper<ServiceCoupon> filter = new LambdaQueryWrapper<ServiceCoupon>()
                .isNull(ServiceCoupon::getOwnerUserId);
        long total = couponMapper.selectCount(filter);
        List<ServiceCoupon> coupons = couponMapper.selectList(filter
                .orderByDesc(ServiceCoupon::getId)
                .last("LIMIT " + size + " OFFSET " + ((page - 1L) * size)));
        coupons.forEach(c -> c.setServiceIds(jdbcTemplate.queryForList(
                "SELECT service_id FROM service_coupon_services WHERE coupon_id=? ORDER BY service_id",
                Long.class, c.getId())));
        PageResponse<ServiceCoupon> response = new PageResponse<>();
        response.setPage(page); response.setSize(size); response.setTotal(total); response.setItems(coupons);
        return response;
    }

    public PageResponse<Map<String, Object>> personalPage(Long owner, String query, int page, int size) {
        validatePage(page, size);
        String search = query == null ? "" : query.trim();
        String from = " FROM service_coupons c JOIN users u ON u.id=c.owner_user_id WHERE c.owner_user_id IS NOT NULL";
        List<Object> args = new java.util.ArrayList<>();
        if (owner != null) {
            from += " AND c.owner_user_id=?";
            args.add(owner);
        }
        if (!search.isEmpty()) {
            from += " AND (LOWER(c.code)=LOWER(?) OR LOWER(u.email)=LOWER(?) OR LOWER(u.username)=LOWER(?))";
            args.addAll(List.of(search, search, search));
        }
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1L) * size);
        PageResponse<Map<String, Object>> result = new PageResponse<>();
        result.setPage(page); result.setSize(size); result.setTotal(total);
        result.setItems(jdbcTemplate.queryForList("""
                SELECT c.id,c.code,c.discount_cents,c.enabled,c.remaining_uses,c.reserved_uses,
                       c.owner_user_id,u.username,c.created_at,
                       CASE WHEN c.rewarded_customer_id IS NULL THEN 'ADMIN' ELSE 'INVITE' END AS source
                """ + from + " ORDER BY c.id DESC LIMIT ? OFFSET ?", args.toArray()));
        return result;
    }

    public ServiceCoupon grantPersonal(long owner, long cents, Long customer) {
        if (cents < 1 || cents > 100_000_000) throw badRequest("优惠券面额无效");
        ServiceCoupon coupon = new ServiceCoupon();
        coupon.setCode("SUB-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
        coupon.setDiscountCents(cents);
        coupon.setOwnerUserId(owner);
        coupon.setRewardedCustomerId(customer);
        coupon.setRemainingUses(1);
        coupon.setReservedUses(0);
        coupon.setEnabled(true);
        coupon.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        coupon.setUpdatedAt(coupon.getCreatedAt());
        couponMapper.insert(coupon);
        return coupon;
    }

    public ServiceCoupon grantPersonal(String account, long cents) {
        if (account == null || account.isBlank()) throw badRequest("请填写用户邮箱或用户名");
        List<Long> users = jdbcTemplate.queryForList("""
                SELECT id FROM users WHERE status='ACTIVE' AND (email=? OR username=?)
                """, Long.class, account.trim(), account.trim());
        if (users.size() != 1) throw badRequest("未找到唯一的有效用户，请核对邮箱或用户名");
        return grantPersonal(users.get(0), cents, null);
    }

    public void revokePersonal(long id) {
        int updated = jdbcTemplate.update("""
                UPDATE service_coupons SET enabled=FALSE,updated_at=?
                WHERE id=? AND owner_user_id IS NOT NULL AND enabled=TRUE AND remaining_uses=1 AND reserved_uses=0
                """, LocalDateTime.now(ZoneOffset.UTC), id);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "只能撤销可用且未预留的个人优惠券");
    }

    private void validatePage(int page, int size) {
        if (page < 1 || page > 1_000_000) throw badRequest("page is out of range");
        if (!List.of(10, 20, 50, 100).contains(size)) throw badRequest("size must be one of 10, 20, 50, 100");
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
        if (coupon == null || coupon.getOwnerUserId() != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found");
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
        if (coupon == null || coupon.getOwnerUserId() != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found");
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
