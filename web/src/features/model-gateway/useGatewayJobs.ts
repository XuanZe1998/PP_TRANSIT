import { onBeforeUnmount, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/utils/http'
import { createPollingTask } from '@/utils/polling'

/** Observe persisted server jobs without submitting duplicate synchronization work. */
export function useGatewayJobs(rows: Ref<any[]>, loading: Ref<boolean>, load: () => Promise<void>, reloadAll: () => Promise<void>) {
  const pending = new Set<string>()
  const poll = createPollingTask({
    interval: 3000,
    maxAttempts: Number.POSITIVE_INFINITY,
    async run(_: string, context) {
      let completed = false
      for (const id of [...pending]) {
        try {
          const { data } = await http.get(`/api/admin/api/gateway/jobs/${id}`)
          if (!context.isCurrent()) return
          if (['QUEUED', 'RUNNING'].includes(data.status)) continue
          pending.delete(id)
          completed = true
          if (data.status === 'SUCCESS') ElMessage.success(data.message)
          else if (data.status === 'PARTIAL') ElMessage.warning(data.message)
          else ElMessage.error(data.message || '分组同步失败')
        } catch (cause) {
          if (!context.isCurrent()) return
          // Missing/deleted jobs cannot recover; transient network failures can.
          if ((cause as { response?: { status?: number } }).response?.status === 404) pending.delete(id)
        }
      }
      if (!context.isCurrent()) return
      if (completed) await reloadAll()
      else if (!loading.value && rows.value.some(row => ['QUEUED', 'RUNNING'].includes(row.status || row.sync_status))) await load()
    }
  })
  onBeforeUnmount(() => { poll.dispose(); pending.clear() })
  return { trackJob: (id: string) => { if (id) pending.add(id) }, startPolling: () => poll.start('gateway') }
}
