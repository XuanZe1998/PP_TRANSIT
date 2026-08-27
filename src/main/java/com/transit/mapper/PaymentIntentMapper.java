package com.transit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.transit.model.PaymentIntent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentIntentMapper extends BaseMapper<PaymentIntent> {}
