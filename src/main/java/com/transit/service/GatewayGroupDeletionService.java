package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Remove a disabled local group without racing persistent directory/sync jobs. */
@Service
@RequiredArgsConstructor
public class GatewayGroupDeletionService {
    private final JdbcTemplate jdbc;
    private final AdminChannelService channels;

    @Transactional
    public void deleteDisabled(long channel) {
        var sites = jdbc.queryForList("SELECT site_id FROM upstream_site_channels WHERE channel_id=?", Long.class, channel);
        if (sites.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分组不存在，请刷新列表");
        long site = sites.get(0);
        String lockId = "delete-" + UUID.randomUUID();
        try {
            // Use the same persistent site/group lock namespace as GatewaySyncJobs.
            jdbc.update("INSERT INTO gateway_site_sync_locks(site_id,job_id) VALUES(?,?)", site, lockId);
            jdbc.update("INSERT INTO gateway_sync_locks(channel_id,job_id) VALUES(?,?)", channel, lockId);
            var disabled = jdbc.queryForList("SELECT id FROM channels WHERE id=? AND enabled=FALSE AND source_code<>'nvidia' FOR UPDATE", Long.class, channel);
            if (disabled.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "请先停用分组后再删除；内置渠道不支持此操作");
            channels.delete(channel);
        } catch (DuplicateKeyException busy) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分组或所属上游正在同步，请等待任务结束后再删除");
        } finally {
            jdbc.update("DELETE FROM gateway_sync_locks WHERE channel_id=? AND job_id=?", channel, lockId);
            jdbc.update("DELETE FROM gateway_site_sync_locks WHERE site_id=? AND job_id=?", site, lockId);
        }
    }
}
