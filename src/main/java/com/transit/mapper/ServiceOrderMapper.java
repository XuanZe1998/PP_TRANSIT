package com.transit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.transit.model.ServiceOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ServiceOrderMapper extends BaseMapper<ServiceOrder> {
    @Select("SELECT id FROM users WHERE id = #{userId} FOR UPDATE")
    Long lockBuyer(Long userId);
}
