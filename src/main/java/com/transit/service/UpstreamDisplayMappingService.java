package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.UpstreamDisplayMappingMapper;
import com.transit.model.UpstreamDisplayMapping;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UpstreamDisplayMappingService {
    private final UpstreamDisplayMappingMapper mapper;
    private final JdbcTemplate jdbc;

    public List<Map<String,Object>> list() {
        return jdbc.query("""
                SELECT c.id channelId,c.name internalName,c.source_code sourceCode,c.type channelType,
                       m.id,m.public_code publicCode,m.public_name publicName,m.badge_text badgeText,
                       m.badge_color badgeColor,COALESCE(m.sort_order,100) sortOrder,COALESCE(m.enabled,TRUE) enabled
                FROM channels c LEFT JOIN upstream_display_mappings m ON m.channel_id=c.id
                ORDER BY COALESCE(m.sort_order,100),c.id
                """, (rs, rowNum) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("channelId", rs.getLong("channelId"));
            row.put("internalName", rs.getString("internalName"));
            row.put("sourceCode", rs.getString("sourceCode"));
            row.put("channelType", rs.getString("channelType"));
            Object id = rs.getObject("id");
            row.put("id", id == null ? null : rs.getLong("id"));
            row.put("publicCode", rs.getString("publicCode"));
            row.put("publicName", rs.getString("publicName"));
            row.put("badgeText", rs.getString("badgeText"));
            row.put("badgeColor", rs.getString("badgeColor"));
            row.put("sortOrder", rs.getInt("sortOrder"));
            row.put("enabled", rs.getBoolean("enabled"));
            return row;
        });
    }

    @Transactional
    public UpstreamDisplayMapping save(Long channelId, UpstreamDisplayMapping request) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE id=?", Integer.class, channelId);
        if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "渠道不存在");
        UpstreamDisplayMapping row = mapper.selectOne(new LambdaQueryWrapper<UpstreamDisplayMapping>().eq(UpstreamDisplayMapping::getChannelId, channelId));
        if (row == null) { row = new UpstreamDisplayMapping(); row.setChannelId(channelId); row.setCreatedAt(LocalDateTime.now()); }
        row.setPublicCode(code(request.getPublicCode()));
        row.setPublicName(required(request.getPublicName(), "公开显示名", 120));
        row.setBadgeText(optional(request.getBadgeText(), "智能路由", 40));
        row.setBadgeColor(color(request.getBadgeColor()));
        row.setSortOrder(request.getSortOrder() == null ? 100 : Math.max(0, Math.min(9999, request.getSortOrder())));
        row.setEnabled(!Boolean.FALSE.equals(request.getEnabled())); row.setUpdatedAt(LocalDateTime.now());
        if (row.getId() == null) mapper.insert(row); else mapper.updateById(row);
        return row;
    }
    private String code(String value) { String v=required(value,"公开代号",80).toLowerCase(Locale.ROOT); if(!v.matches("[a-z0-9][a-z0-9_-]*")) throw bad("公开代号只能包含小写字母、数字、- 和 _"); return v; }
    private String color(String value) { String v=optional(value,"#2563eb",16); if(!v.matches("#[0-9a-fA-F]{6}")) throw bad("徽标颜色必须是六位十六进制颜色"); return v.toLowerCase(Locale.ROOT); }
    private String required(String v,String f,int n){if(v==null||v.isBlank())throw bad(f+"不能为空");return optional(v,"",n);}
    private String optional(String v,String d,int n){String x=v==null||v.isBlank()?d:v.trim();if(x.length()>n)throw bad("字段长度不能超过"+n);return x;}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
