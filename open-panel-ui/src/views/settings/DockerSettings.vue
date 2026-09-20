<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../../lib/api'

const emit = defineEmits(['availability-change', 'message', 'error'])

const dockerOverview = ref({ available: false, error: '', containers: [], activeJob: null })
const dockerJob = ref(null)
const dockerAction = ref('')
const composeDialog = ref(false)
const composeLoading = ref(false)
const composeView = ref(null)
const cleanupDialog = ref(false)
const cleanupLoading = ref(false)
const cleanupPreviewLoading = ref(false)
const cleanupPreview = ref(null)
const cleanupSelectedImageIds = ref([])
const dockerClock = ref(Date.now())
const dockerOverviewLoadedAt = ref(Date.now())
let dockerTimer
let dockerClockTimer

const dockerJobRunning = computed(() => ['queued', 'running', 'cancelling'].includes(dockerJob.value?.status))
const dockerUpdates = computed(() => dockerOverview.value.containers.filter(container => container.updateAvailable).length)
const sortedDockerContainers = computed(() => [...dockerOverview.value.containers]
  .sort((left, right) => Number(Boolean(right.updateAvailable)) - Number(Boolean(left.updateAvailable))))
const cleanupSelectedImages = computed(() => {
  const selected = new Set(cleanupSelectedImageIds.value)
  return (cleanupPreview.value?.images || []).filter(image => selected.has(image.cleanupTarget || image.id))
})
const cleanupSelectedSize = computed(() => cleanupSelectedImages.value.reduce((total, image) => total + image.size, 0))
const cleanupAllSelected = computed({
  get: () => Boolean(cleanupPreview.value?.images?.length)
    && cleanupSelectedImageIds.value.length === cleanupPreview.value.images.length,
  set: selected => {
    cleanupSelectedImageIds.value = selected
      ? cleanupPreview.value?.images?.map(image => image.cleanupTarget || image.id) || []
      : []
  }
})
const cleanupSelectionIndeterminate = computed(() => cleanupSelectedImageIds.value.length > 0 && !cleanupAllSelected.value)

onMounted(() => {
  loadDockerOverview()
  dockerClockTimer = setInterval(() => { dockerClock.value = Date.now() }, 1000)
})
onBeforeUnmount(() => {
  clearTimeout(dockerTimer)
  clearInterval(dockerClockTimer)
})

// 加载 Docker 概览并向父页面同步功能是否可用。
async function loadDockerOverview() {
  try {
    dockerOverview.value = await api('/api/admin/docker', { cache: 'no-store' })
    dockerOverviewLoadedAt.value = Date.now()
    emit('availability-change', Boolean(dockerOverview.value.available))
    if (dockerOverview.value.activeJob && dockerOverview.value.activeJob.id !== dockerJob.value?.id) {
      dockerJob.value = dockerOverview.value.activeJob
      pollDockerJob()
    }
  } catch {
    dockerOverview.value = { available: false, error: '', containers: [], activeJob: null }
    emit('availability-change', false)
  }
}

// 启动远端镜像摘要检查任务。
async function startDockerCheck() {
  clearTimeout(dockerTimer)
  try {
    dockerJob.value = await api('/api/admin/docker/check', { method: 'POST' })
    pollDockerJob()
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 为指定容器创建更新任务。
async function startDockerUpdate(container) {
  clearTimeout(dockerTimer)
  try {
    dockerJob.value = await api('/api/admin/docker/update', {
      method: 'POST',
      body: JSON.stringify({ containerId: container.id })
    })
    pollDockerJob()
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 轮询 Docker 后台任务，并在完成后刷新容器列表。
async function pollDockerJob() {
  clearTimeout(dockerTimer)
  if (!dockerJob.value?.id) return
  try {
    dockerJob.value = await api(`/api/admin/docker/jobs/${dockerJob.value.id}`)
    if (dockerJobRunning.value) {
      dockerTimer = setTimeout(pollDockerJob, 700)
      return
    }
    const completedCheck = dockerJob.value.type === 'check' && dockerJob.value.status === 'completed'
    await loadDockerOverview()
    if (completedCheck) emit('message', '镜像检测完成，容器列表已刷新')
    if (dockerJob.value.status === 'failed') emit('error', dockerJob.value.error || 'Docker 任务失败')
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 请求中断当前可取消的 Docker 任务。
async function cancelDockerJob() {
  if (!dockerJob.value?.id || !dockerJob.value.cancellable) return
  try {
    await api(`/api/admin/docker/jobs/${dockerJob.value.id}`, { method: 'DELETE' })
    pollDockerJob()
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 执行容器启动、停止或重启操作。
async function runDockerAction(container, action) {
  const key = `${container.id}:${action}`
  dockerAction.value = key
  try {
    await api('/api/admin/docker/containers/action', {
      method: 'POST',
      body: JSON.stringify({ containerId: container.id, action })
    })
    await loadDockerOverview()
  } catch (requestError) {
    emit('error', requestError.message)
  } finally {
    dockerAction.value = ''
  }
}

// 获取并打开指定容器的 Compose 配置。
async function showDockerCompose(container) {
  composeDialog.value = true
  composeLoading.value = true
  composeView.value = null
  try {
    composeView.value = await api(`/api/admin/docker/containers/${encodeURIComponent(container.id)}/compose`)
  } catch (requestError) {
    emit('error', requestError.message)
    composeDialog.value = false
  } finally {
    composeLoading.value = false
  }
}

// 复制 Compose 内容，并兼容不支持 Clipboard API 的浏览器。
async function copyDockerCompose() {
  const content = composeView.value?.content
  if (!content) return
  try {
    await navigator.clipboard.writeText(content)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = content
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  emit('message', 'docker-compose.yaml 已复制')
}

// 加载可清理镜像预览并默认全选所有目标。
async function openCleanupDialog() {
  cleanupDialog.value = true
  cleanupPreviewLoading.value = true
  cleanupPreview.value = null
  cleanupSelectedImageIds.value = []
  try {
    cleanupPreview.value = await api('/api/admin/docker/images/unused')
    cleanupSelectedImageIds.value = cleanupPreview.value.images.map(image => image.cleanupTarget || image.id)
  } catch (requestError) {
    emit('error', requestError.message)
    cleanupDialog.value = false
  } finally {
    cleanupPreviewLoading.value = false
  }
}

// 删除用户选中的未使用镜像或冗余标签。
async function cleanupDockerImages() {
  if (!cleanupSelectedImageIds.value.length) return
  cleanupLoading.value = true
  try {
    const result = await api('/api/admin/docker/images/unused', {
      method: 'DELETE',
      body: JSON.stringify({ imageIds: cleanupSelectedImageIds.value })
    })
    cleanupDialog.value = false
    const cleanupSummary = []
    if (result.deletedImages) cleanupSummary.push(`已删除 ${result.deletedImages} 个未使用镜像，释放约 ${formatBytes(result.spaceReclaimed)}`)
    if (result.removedTags) cleanupSummary.push(`已移除 ${result.removedTags} 个冗余标签`)
    if (result.skippedImages) cleanupSummary.push(`${result.skippedImages} 个目标已跳过`)
    emit('message', cleanupSummary.length ? cleanupSummary.join('，') : '没有可清理的镜像或标签')
    dockerJob.value = null
    await loadDockerOverview()
  } catch (requestError) {
    emit('error', requestError.message)
  } finally {
    cleanupLoading.value = false
  }
}

// 返回镜像列表中优先展示的名称。
function cleanupImageName(image) {
  return image.references?.[0] || '未命名镜像'
}

// 组合镜像短 ID、大小和标签说明。
function cleanupImageDetail(image) {
  const id = String(image.id || '').replace(/^sha256:/, '').slice(0, 12) || '未知 ID'
  if (image.tagOnly) return `${id} · 仅移除冗余标签`
  const more = image.references?.length > 1 ? ` · 另有 ${image.references.length - 1} 个标签` : ''
  return `${id} · ${formatBytes(image.size)}${more}`
}

// 将 Docker 原始状态转换为中文标签。
function dockerStateLabel(state) {
  return ({ running: '运行中', exited: '已停止', created: '已创建', paused: '已暂停', restarting: '重启中', dead: '异常退出' })[state] || state || '未知'
}

// 判断容器是否处于可停止或重启状态。
function containerIsActive(container) {
  return ['running', 'paused', 'restarting'].includes(container.state)
}

// 判断容器是否为当前更新任务的目标。
function containerIsUpdating(container) {
  if (!dockerJobRunning.value || dockerJob.value?.type !== 'update' || !dockerJob.value.containerId) return false
  const target = dockerJob.value.containerId
  return container.id === target || container.id.startsWith(target) || target.startsWith(container.id)
}

// 将更新阶段转换为用户可读的进度说明。
function dockerPhaseLabel(phase) {
  return ({
    queued: '等待开始更新',
    loading: '正在读取容器配置',
    pulling: '正在拉取新镜像',
    recreating: '正在重建容器',
    cancelling: '正在中断更新'
  })[phase] || '正在更新容器'
}

// 将秒数格式化为紧凑运行时长。
function formatDuration(seconds) {
  const value = Math.max(0, Number(seconds) || 0)
  const days = Math.floor(value / 86400)
  const hours = Math.floor(value % 86400 / 3600)
  const minutes = Math.floor(value % 3600 / 60)
  if (days) return `${days}天${hours ? ` ${hours}小时` : ''}`
  if (hours) return `${hours}小时${minutes ? ` ${minutes}分钟` : ''}`
  if (minutes) return `${minutes}分钟`
  return `${Math.floor(value)}秒`
}

// 根据概览加载时间持续计算运行中容器的实时运行时长。
function containerUptime(container) {
  if (container.state !== 'running') return 0
  return container.uptimeSeconds + Math.max(0, Math.floor((dockerClock.value - dockerOverviewLoadedAt.value) / 1000))
}

// 将字节数格式化为合适的容量单位。
function formatBytes(bytes) {
  const value = Math.max(0, Number(bytes) || 0)
  if (value < 1024) return `${value} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let size = value
  let unit = -1
  do { size /= 1024; unit++ } while (size >= 1024 && unit < units.length - 1)
  return `${size.toFixed(size >= 10 ? 1 : 2)} ${units[unit]}`
}

// 根据任务类型和状态生成任务标题。
function dockerJobTitle(job) {
  if (!job) return ''
  if (job.type === 'check') return job.status === 'completed' ? '镜像检查完成' : job.status === 'cancelled' ? '镜像检查已中断' : '正在检查镜像更新'
  return job.status === 'completed' ? `${job.containerName || '容器'} 更新完成` : job.status === 'cancelled' ? '容器更新已中断' : `正在更新 ${job.containerName || '容器'}`
}

// 将任务日志时间戳格式化为本地时间。
function formatLogTime(timestamp) {
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <section class="settings-section">
    <div class="section-heading">
      <div>
        <h2>容器镜像</h2>
        <p>项目启动后及每 3 小时自动检测镜像；容器只会在手动点击更新后重建</p>
      </div>
      <div class="action-row">
        <v-btn
          variant="outlined"
          prepend-icon="mdi-cloud-sync-outline"
          :loading="dockerJobRunning && dockerJob?.type === 'check'"
          :disabled="dockerJobRunning || !!dockerAction"
          @click="startDockerCheck"
        >重新检测</v-btn>
        <v-btn
          variant="outlined"
          color="error"
          prepend-icon="mdi-delete-sweep-outline"
          :disabled="dockerJobRunning || !!dockerAction"
          @click="openCleanupDialog"
        >清理镜像</v-btn>
      </div>
    </div>

    <div class="docker-summary">
      <span><strong>{{ dockerOverview.containers.length }}</strong><small>容器</small></span>
      <span><strong>{{ dockerUpdates }}</strong><small>可更新</small></span>
      <span><strong>{{ dockerOverview.containers.filter(item => item.state === 'running').length }}</strong><small>运行中</small></span>
    </div>

    <div v-if="dockerOverview.containers.length" class="docker-list">
      <article
        v-for="container in sortedDockerContainers"
        :key="container.id"
        class="docker-row"
        :class="{ 'docker-row--updating': containerIsUpdating(container) }"
      >
        <div class="docker-card-head">
          <div class="docker-copy">
            <strong>{{ container.name }}</strong>
            <small class="docker-image" :title="container.image">{{ container.image || '未知镜像' }}</small>
          </div>
          <div class="docker-card-badges">
            <v-chip v-if="container.self" size="x-small" variant="tonal">当前实例</v-chip>
            <v-chip v-else-if="container.checkError" size="x-small" color="error" variant="tonal">检测失败</v-chip>
            <v-chip v-else-if="!container.updatable" size="x-small" variant="tonal">不可更新</v-chip>
            <v-chip v-else-if="container.updateAvailable" size="x-small" color="warning" variant="tonal">发现更新</v-chip>
          </div>
        </div>

        <div class="docker-card-meta" :class="{ 'docker-error': container.checkError }">
          <v-icon :icon="containerIsUpdating(container) ? 'mdi-sync' : container.state === 'running' ? 'mdi-clock-outline' : 'mdi-information-outline'" size="15" />
          <span v-if="containerIsUpdating(container)">{{ dockerPhaseLabel(dockerJob.phase) }}</span>
          <span v-else-if="container.state === 'running'">已运行 {{ formatDuration(containerUptime(container)) }}</span>
          <span v-else>{{ dockerStateLabel(container.state) }} · {{ container.status }}</span>
          <span v-if="container.checkError || container.reason" class="docker-note">{{ container.checkError || container.reason }}</span>
        </div>

        <div v-if="containerIsUpdating(container)" class="docker-card-progress">
          <div><span>{{ dockerJob.status === 'cancelling' ? '正在停止' : dockerJob.cancellable ? '更新中' : '正在安全替换' }}</span><strong>{{ dockerJob.progress }}%</strong></div>
          <v-progress-linear :model-value="dockerJob.progress" color="primary" height="5" rounded />
          <v-btn
            icon="mdi-stop-circle-outline"
            size="small"
            variant="text"
            color="error"
            aria-label="停止更新"
            :disabled="!dockerJob.cancellable || dockerJob.status === 'cancelling'"
            @click="cancelDockerJob"
          ><v-icon icon="mdi-stop-circle-outline" /><v-tooltip activator="parent">停止更新</v-tooltip></v-btn>
        </div>
        <div v-else class="docker-controls">
          <v-btn icon="mdi-file-code-outline" size="small" variant="text" aria-label="查看 Docker Compose" @click="showDockerCompose(container)">
            <v-icon icon="mdi-file-code-outline" /><v-tooltip activator="parent">查看 docker-compose.yaml</v-tooltip>
          </v-btn>
          <span class="docker-control-spacer" />
          <template v-if="!container.self">
            <v-btn
              v-if="containerIsActive(container)"
              icon="mdi-stop"
              size="small"
              variant="text"
              color="error"
              aria-label="停止容器"
              :loading="dockerAction === `${container.id}:stop`"
              :disabled="dockerJobRunning || !!dockerAction"
              @click="runDockerAction(container, 'stop')"
            ><v-icon icon="mdi-stop" /><v-tooltip activator="parent">停止容器</v-tooltip></v-btn>
            <v-btn
              v-else
              icon="mdi-play"
              size="small"
              variant="text"
              color="success"
              aria-label="启动容器"
              :loading="dockerAction === `${container.id}:start`"
              :disabled="dockerJobRunning || !!dockerAction"
              @click="runDockerAction(container, 'start')"
            ><v-icon icon="mdi-play" /><v-tooltip activator="parent">启动容器</v-tooltip></v-btn>
            <v-btn
              icon="mdi-restart"
              size="small"
              variant="text"
              color="primary"
              aria-label="重启容器"
              :loading="dockerAction === `${container.id}:restart`"
              :disabled="!containerIsActive(container) || dockerJobRunning || !!dockerAction"
              @click="runDockerAction(container, 'restart')"
            ><v-icon icon="mdi-restart" /><v-tooltip activator="parent">重启容器</v-tooltip></v-btn>
          </template>
          <v-btn
            v-if="container.updatable"
            icon="mdi-cloud-download-outline"
            size="small"
            variant="text"
            color="secondary"
            aria-label="更新容器"
            :disabled="dockerJobRunning || !!dockerAction"
            @click="startDockerUpdate(container)"
          ><v-icon icon="mdi-cloud-download-outline" /><v-tooltip activator="parent">更新容器</v-tooltip></v-btn>
        </div>
      </article>
    </div>
    <div v-else class="docker-empty">
      <v-icon icon="mdi-package-variant-closed" size="32" />
      <span>当前没有 Docker 容器</span>
    </div>
  </section>

  <section v-if="dockerJob" class="settings-section docker-task-section">
    <div class="docker-task-head">
      <div>
        <strong>{{ dockerJobTitle(dockerJob) }}</strong>
        <small v-if="dockerJob.type === 'check' && dockerJob.totalItems">
          {{ dockerJob.completedItems }} / {{ dockerJob.totalItems }} 个镜像
        </small>
        <small v-else-if="dockerJob.image">{{ dockerJob.image }}</small>
      </div>
      <span>{{ dockerJob.progress }}%</span>
      <v-btn
        v-if="dockerJobRunning"
        color="error"
        variant="text"
        prepend-icon="mdi-stop-circle-outline"
        :disabled="!dockerJob.cancellable || dockerJob.status === 'cancelling'"
        @click="cancelDockerJob"
      >{{ dockerJob.status === 'cancelling' ? '正在停止' : dockerJob.cancellable ? '中断' : '正在安全替换' }}</v-btn>
    </div>
    <v-progress-linear
      :model-value="dockerJob.progress"
      :indeterminate="dockerJob.status === 'queued'"
      :color="dockerJob.status === 'failed' ? 'error' : dockerJob.status === 'completed' ? 'success' : 'secondary'"
      height="7"
      rounded
    />
    <div class="docker-log" role="log" aria-live="polite">
      <div v-for="(entry, index) in dockerJob.logs" :key="`${entry.timestamp}-${index}`" :class="`log-${entry.level}`">
        <time>{{ formatLogTime(entry.timestamp) }}</time>
        <v-icon :icon="entry.level === 'error' ? 'mdi-alert-circle-outline' : entry.level === 'success' ? 'mdi-check-circle-outline' : entry.level === 'warn' ? 'mdi-alert-outline' : 'mdi-chevron-right'" size="15" />
        <span>{{ entry.message }}</span>
      </div>
    </div>
  </section>

  <v-dialog v-model="composeDialog" max-width="900">
    <v-card>
      <v-card-title class="compose-title">
        <span>{{ composeView?.containerName || 'Docker Compose' }}</span>
        <v-spacer />
        <v-btn icon="mdi-close" variant="text" aria-label="关闭" @click="composeDialog = false"><v-icon icon="mdi-close" /></v-btn>
      </v-card-title>
      <v-card-text>
        <v-progress-linear v-if="composeLoading" indeterminate color="secondary" />
        <pre v-else-if="composeView" class="compose-source"><code>{{ composeView.content }}</code></pre>
      </v-card-text>
      <v-card-actions v-if="composeView">
        <small class="compose-filename">{{ composeView.filename }}</small>
        <v-spacer />
        <v-btn prepend-icon="mdi-content-copy" variant="outlined" @click="copyDockerCompose">复制</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-dialog v-model="cleanupDialog" max-width="680" scrollable persistent>
    <v-card>
      <v-card-title>清理镜像和冗余标签</v-card-title>
      <v-card-text class="cleanup-dialog-content">
        <v-progress-linear v-if="cleanupPreviewLoading" indeterminate color="secondary" />
        <template v-else-if="cleanupPreview">
          <div v-if="cleanupPreview.images.length" class="cleanup-summary">
            <v-checkbox-btn
              v-model="cleanupAllSelected"
              label="全选"
              color="primary"
              :indeterminate="cleanupSelectionIndeterminate"
              :disabled="cleanupLoading"
            />
            <strong>已选 {{ cleanupSelectedImages.length }} / {{ cleanupPreview.images.length }}</strong>
            <small>合计 {{ formatBytes(cleanupSelectedSize) }}</small>
          </div>
          <div v-if="cleanupPreview.images.length" class="cleanup-image-list">
            <div v-for="image in cleanupPreview.images" :key="image.cleanupTarget || image.id" class="cleanup-image-row">
              <v-checkbox-btn
                v-model="cleanupSelectedImageIds"
                :value="image.cleanupTarget || image.id"
                color="primary"
                :disabled="cleanupLoading"
                :aria-label="`选择镜像 ${cleanupImageName(image)}`"
              />
              <v-icon :icon="image.tagOnly ? 'mdi-tag-remove-outline' : 'mdi-package-variant'" size="22" />
              <span>
                <strong :title="image.references?.join('\n')">{{ cleanupImageName(image) }}</strong>
                <small>{{ cleanupImageDetail(image) }}</small>
              </span>
            </div>
          </div>
          <div v-else class="cleanup-empty">
            <v-icon icon="mdi-check-circle-outline" size="30" />
            <span>没有可清理的未使用镜像</span>
          </div>
        </template>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn :disabled="cleanupLoading" @click="cleanupDialog = false">取消</v-btn>
        <v-btn
          color="error"
          prepend-icon="mdi-delete-sweep-outline"
          :loading="cleanupLoading"
          :disabled="cleanupPreviewLoading || !cleanupSelectedImageIds.length"
          @click="cleanupDockerImages"
        >确认清理（{{ cleanupSelectedImageIds.length }}）</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.settings-section { padding-block: 28px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.section-heading h2 { margin: 0; font-size: 1.08rem; letter-spacing: 0; }
.section-heading p { margin: 5px 0 0; color: rgba(var(--v-theme-on-surface),.56); font-size: .84rem; }
.action-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.docker-summary { display: flex; gap: 28px; margin-bottom: 18px; }
.docker-summary > span { display: grid; min-width: 72px; }
.docker-summary strong { font-size: 1.5rem; line-height: 1.1; }
.docker-summary small { margin-top: 5px; color: rgba(var(--v-theme-on-surface),.56); font-size: .75rem; }
.docker-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 330px), 1fr)); gap: 10px; }
.docker-row { display: flex; min-width: 0; min-height: 142px; flex-direction: column; padding: 13px 14px 10px; border: 1px solid rgba(var(--v-theme-on-surface),.11); border-radius: 8px; background: rgb(var(--v-theme-surface)); transition: border-color .18s ease, background-color .18s ease; }
.docker-row:hover { border-color: rgba(var(--v-theme-primary),.3); }
.docker-row--updating { border-color: rgba(var(--v-theme-primary),.52); background: rgba(var(--v-theme-primary),.035); }
.docker-card-head { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: center; min-width: 0; gap: 10px; }
.docker-copy { display: grid; min-width: 0; line-height: 1.25; }
.docker-copy strong, .docker-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.docker-copy strong { font-size: .98rem; font-weight: 700; }
.docker-copy small, .docker-task-head small { margin-top: 3px; color: rgba(var(--v-theme-on-surface),.58); font-size: .76rem; }
.docker-image { font-size: .8rem !important; }
.docker-row--updating .docker-copy strong { color: rgb(var(--v-theme-primary)); }
.docker-card-badges { display: flex; align-self: start; align-items: center; gap: 4px; }
.docker-card-meta { display: flex; min-width: 0; align-items: center; gap: 5px; overflow: hidden; margin-top: 9px; color: rgba(var(--v-theme-on-surface),.58); font-size: .73rem; white-space: nowrap; }
.docker-card-meta > .v-icon { flex: 0 0 auto; }
.docker-card-meta.docker-error { color: rgb(var(--v-theme-error)); }
.docker-note { overflow: hidden; text-overflow: ellipsis; }
.docker-note::before { margin-inline: 2px 7px; color: rgba(var(--v-theme-on-surface),.28); content: '·'; }
.docker-row--updating .docker-card-meta > .v-icon { color: rgb(var(--v-theme-primary)); animation: docker-spin 1.1s linear infinite; }
.docker-card-progress { display: grid; grid-template-columns: minmax(0,1fr) 32px; align-items: center; gap: 4px 8px; margin-top: auto; padding-top: 9px; }
.docker-card-progress > div { display: flex; justify-content: space-between; color: rgba(var(--v-theme-on-surface),.62); font-size: .72rem; font-variant-numeric: tabular-nums; }
.docker-card-progress > .v-progress-linear { grid-column: 1; }
.docker-card-progress > .v-btn { grid-column: 2; grid-row: 1 / 3; }
.docker-controls { display: flex; min-height: 34px; align-items: center; gap: 1px; margin-top: auto; padding-top: 8px; border-top: 1px solid rgba(var(--v-theme-on-surface),.08); }
.docker-control-spacer { flex: 1; }
.docker-controls .v-btn { width: 32px; height: 32px; }
@keyframes docker-spin { to { transform: rotate(360deg); } }
.docker-empty { display: grid; min-height: 180px; place-items: center; align-content: center; gap: 10px; color: rgba(var(--v-theme-on-surface),.52); }
.docker-task-section { display: grid; gap: 14px; }
.docker-task-head { display: grid; grid-template-columns: minmax(0,1fr) auto auto; align-items: center; gap: 14px; }
.docker-task-head > div { display: grid; min-width: 0; }
.docker-task-head > span { color: rgba(var(--v-theme-on-surface),.62); font-variant-numeric: tabular-nums; font-size: .82rem; }
.docker-log { height: 260px; overflow: auto; padding: 10px 12px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgba(var(--v-theme-on-surface),.035); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .75rem; }
.docker-log > div { display: grid; grid-template-columns: 68px 18px minmax(0,1fr); align-items: start; min-height: 25px; gap: 4px; line-height: 1.55; }
.docker-log time { color: rgba(var(--v-theme-on-surface),.44); font-variant-numeric: tabular-nums; }
.docker-log .log-success { color: rgb(var(--v-theme-success)); }
.docker-log .log-warn { color: rgb(var(--v-theme-warning)); }
.docker-log .log-error { color: rgb(var(--v-theme-error)); }
.compose-title { display: flex; align-items: center; min-height: 58px; }
.compose-source { max-height: min(68svh, 720px); overflow: auto; margin: 0; padding: 16px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgba(var(--v-theme-on-surface),.045); color: rgb(var(--v-theme-on-surface)); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: .78rem; line-height: 1.6; white-space: pre; }
.compose-filename { padding-left: 8px; color: rgba(var(--v-theme-on-surface),.56); }
.cleanup-dialog-content { display: grid; min-height: 150px; gap: 14px; }
.cleanup-summary { display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 14px; }
.cleanup-summary strong { font-size: .86rem; }
.cleanup-summary small { color: rgba(var(--v-theme-on-surface),.58); font-size: .76rem; }
.cleanup-image-list { display: grid; max-height: min(52svh, 480px); gap: 2px; overflow-y: auto; }
.cleanup-image-row { display: flex; align-items: center; min-width: 0; gap: 11px; padding: 10px 8px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.08); }
.cleanup-image-row > .v-selection-control { flex: 0 0 auto; }
.cleanup-image-row > .v-icon { flex: 0 0 auto; color: rgba(var(--v-theme-on-surface),.6); }
.cleanup-image-row > span { display: grid; min-width: 0; gap: 3px; }
.cleanup-image-row strong, .cleanup-image-row small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cleanup-image-row strong { font-size: .86rem; }
.cleanup-image-row small { color: rgba(var(--v-theme-on-surface),.54); font-size: .73rem; font-variant-numeric: tabular-nums; }
.cleanup-empty { display: grid; min-height: 120px; place-items: center; align-content: center; gap: 9px; color: rgba(var(--v-theme-on-surface),.54); }
@media (max-width: 820px) {
  .docker-list { grid-template-columns: 1fr; }
}
@media (max-width: 540px) {
  .section-heading { align-items: flex-start; flex-direction: column; }
  .docker-summary { justify-content: space-between; gap: 12px; }
  .docker-row { min-height: 138px; padding-inline: 12px; }
  .docker-card-badges .v-chip { display: none; }
  .docker-task-head { grid-template-columns: minmax(0,1fr) auto; }
  .docker-task-head .v-btn { grid-column: 1 / 3; justify-self: start; }
  .docker-log { height: 220px; }
}
</style>
