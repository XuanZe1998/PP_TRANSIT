package com.transit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.transit.model.OAuthToken;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OAuthTokenMapper extends BaseMapper<OAuthToken> {
}
