package com.transit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.transit.model.OAuthCode;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OAuthCodeMapper extends BaseMapper<OAuthCode> {
}
