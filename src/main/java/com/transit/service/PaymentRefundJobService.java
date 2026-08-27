package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.PaymentRefundJobMapper;
import com.transit.mapper.ServiceOrderMapper;
import com.transit.model.PaymentRefundJob;
import com.transit.model.ServiceOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentRefundJobService {
    private final PaymentRefundJobMapper mapper;
    private final ServiceOrderMapper orderMapper;
    private final PaymentIntentService paymentIntentService;
    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelayString="${payment.refund-jobs.scan-ms:60000}",initialDelayString="${payment.refund-jobs.initial-delay-ms:30000}")
    public void processDue() {
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        // Recover work abandoned by a terminated worker, then atomically claim
        // each row so multiple application nodes cannot issue duplicate refunds.
        jdbcTemplate.update("UPDATE payment_refund_jobs SET status='RETRY',next_attempt_at=?,updated_at=? WHERE status='PROCESSING' AND updated_at<?",
                now, now, now.minusMinutes(15));
        List<PaymentRefundJob> jobs=mapper.selectList(new LambdaQueryWrapper<PaymentRefundJob>()
                .in(PaymentRefundJob::getStatus,"PENDING","RETRY").le(PaymentRefundJob::getNextAttemptAt,now)
                .orderByAsc(PaymentRefundJob::getNextAttemptAt).last("LIMIT 20"));
        for(PaymentRefundJob candidate:jobs) {
            int claimed=jdbcTemplate.update("UPDATE payment_refund_jobs SET status='PROCESSING',updated_at=? WHERE id=? AND status IN ('PENDING','RETRY') AND next_attempt_at<=?",
                    now,candidate.getId(),now);
            if(claimed==1) process(mapper.selectById(candidate.getId()),now);
        }
    }

    private void process(PaymentRefundJob job,LocalDateTime now) {
        int attempts=(job.getAttempts()==null?0:job.getAttempts())+1;job.setAttempts(attempts);job.setUpdatedAt(now);
        try {
            paymentIntentService.refund(job.getPaymentIntentId(),job.getReason());job.setStatus("COMPLETED");job.setLastError(null);mapper.updateById(job);
        } catch(Exception failure) {
            String message=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();job.setLastError(message.substring(0,Math.min(1000,message.length())));
            ServiceOrder order=orderMapper.selectById(job.getServiceOrderId());
            if(order!=null){order.setStatus(attempts>=5?"REFUND_REQUIRES_ACTION":"REFUND_PENDING");order.setFulfillmentStatus(order.getStatus());
                order.setFulfillmentNote(attempts>=5?"自动退款多次失败，已通知管理员人工处理。":"自动退款暂未成功，系统将继续重试。");order.setUpdatedAt(now);orderMapper.updateById(order);}
            if(attempts>=5){job.setStatus("REQUIRES_ACTION");log.error("Refund job {} requires administrator action: {}",job.getId(),job.getLastError());}
            else {job.setStatus("RETRY");job.setNextAttemptAt(now.plusMinutes(Math.min(60,1L<<attempts)));}
            mapper.updateById(job);
        }
    }
}
