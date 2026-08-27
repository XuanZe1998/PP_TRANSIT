package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.PublicContextPricing;
import com.transit.dto.PublicModel;
import com.transit.mapper.ModelContextPricingPolicyMapper;
import com.transit.model.ModelContextPricingPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelContextPricingService {
    public static final BigDecimal FIXED_MULTIPLIER = new BigDecimal("2.000000");
    private final ModelContextPricingPolicyMapper mapper;
    private final JdbcTemplate jdbc;

    public ModelContextPricingPolicy find(String model) {
        if (model == null || model.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<ModelContextPricingPolicy>()
                .eq(ModelContextPricingPolicy::getPublicModelName, model).last("LIMIT 1"));
    }

    public boolean highTier(String model, int promptTokens) {
        ModelContextPricingPolicy policy = find(model);
        return policy != null && Boolean.TRUE.equals(policy.getEnabled()) && policy.getThresholdTokens() != null
                && promptTokens > policy.getThresholdTokens();
    }

    public BigDecimal salePrice(String model, int promptTokens, BigDecimal base) {
        BigDecimal value = base == null ? BigDecimal.ZERO : base;
        return highTier(model, promptTokens) ? value.multiply(FIXED_MULTIPLIER) : value;
    }

    public void enrich(List<PublicModel> models) {
        if (models == null) return;
        for (PublicModel model : models) {
            ModelContextPricingPolicy policy = find(model.getPublicName());
            boolean enabled = policy != null && Boolean.TRUE.equals(policy.getEnabled());
            BigDecimal input = nz(model.getMaxInputPricePerMillion());
            BigDecimal output = nz(model.getMaxOutputPricePerMillion());
            model.setContextPricing(PublicContextPricing.builder()
                    .enabled(enabled).basis("ACTUAL_INPUT_TOKENS")
                    .thresholdTokens(enabled ? policy.getThresholdTokens() : null)
                    .multiplier(enabled ? FIXED_MULTIPLIER : BigDecimal.ONE)
                    .baseInputPrice(input).baseOutputPrice(output)
                    .longInputPrice(enabled ? input.multiply(FIXED_MULTIPLIER) : input)
                    .longOutputPrice(enabled ? output.multiply(FIXED_MULTIPLIER) : output)
                    .priceUnit("USD / 1M Token")
                    .message(enabled ? policy.getVerificationNote() : null).build());
        }
    }

    @Transactional
    public ModelContextPricingPolicy save(String publicModelName, ModelContextPricingPolicy request) {
        String model = publicModelName == null ? "" : publicModelName.trim();
        if (model.isBlank() || model.length() > 160) throw bad("模型名称无效");
        int threshold = request.getThresholdTokens() == null ? 0 : request.getThresholdTokens();
        if (threshold < 1 || threshold > 100_000_000) throw bad("上下文阈值必须在 1 到 100000000 之间");
        if (Boolean.TRUE.equals(request.getEnabled())) validateUniformPaidTokenRoutes(model);
        ModelContextPricingPolicy row = find(model);
        if (row == null) { row = new ModelContextPricingPolicy(); row.setPublicModelName(model); }
        row.setEnabled(Boolean.TRUE.equals(request.getEnabled())); row.setThresholdTokens(threshold);
        row.setMultiplier(FIXED_MULTIPLIER);
        String note=request.getVerificationNote()==null?null:request.getVerificationNote().trim();
        if(note!=null&&note.length()>500)throw bad("核验说明不能超过 500 个字符");
        row.setVerificationNote(note); row.setUpdatedAt(LocalDateTime.now());
        if(row.getId()==null)mapper.insert(row);else mapper.updateById(row);
        return row;
    }

    private void validateUniformPaidTokenRoutes(String model) {
        List<java.util.Map<String,Object>> rows=jdbc.queryForList("""
                SELECT UPPER(COALESCE(pricing_unit,'TOKEN')) pricing_unit_value,
                       UPPER(COALESCE(billing_mode,'PAID')) billing_mode_value,
                       input_price_per_million input_price_value,output_price_per_million output_price_value
                FROM model_mappings WHERE public_model_name=? AND enabled=TRUE
                """,model);
        if(rows.isEmpty())throw bad("该模型没有已启用路由");
        if(rows.stream().anyMatch(r->!"TOKEN".equals(String.valueOf(value(r,"pricing_unit_value")))
                ||!"PAID".equals(String.valueOf(value(r,"billing_mode_value")))))
            throw bad("长上下文翻倍只适用于付费 Token 模型");
        long input=rows.stream().map(r->nz(value(r,"input_price_value"))).distinct().count();
        long output=rows.stream().map(r->nz(value(r,"output_price_value"))).distinct().count();
        if(input>1||output>1)throw new ResponseStatusException(HttpStatus.CONFLICT,"各路由基础售价不一致，请先统一售价");
    }
    private BigDecimal nz(BigDecimal v){return v==null?BigDecimal.ZERO:v;}
    private BigDecimal nz(Object v){return v==null?BigDecimal.ZERO:new BigDecimal(v.toString()).stripTrailingZeros();}
    private Object value(java.util.Map<String,Object> row,String key){
        if(row.containsKey(key))return row.get(key);
        return row.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).map(java.util.Map.Entry::getValue).findFirst().orElse(null);
    }
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
