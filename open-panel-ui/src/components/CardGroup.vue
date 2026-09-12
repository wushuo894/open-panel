<script setup>
import { ref } from 'vue'
import { api } from '../lib/api'
import { appUrl } from '../lib/paths'
import { appState } from '../stores/app'

const props = defineProps({
  group: Object,
  cards: Array,
  statuses: Object,
  cover: Boolean,
  hideHeader: Boolean,
  centered: Boolean
})
const dockerActionLoading = ref({})
const actionError = ref('')

function open(card) {
  const url = cardUrl(card)
  if (card.type === 'system' || !url) return
  if (card.openTarget === 'self') window.location.href = url
  else window.open(url, '_blank', 'noopener,noreferrer')
}

function cardUrl(card) {
  if (card.resolvedUrl) return card.resolvedUrl
  const source = card[card.type]
  if (!source) return ''
  const externalFirst = appState.network === 'external'
  return externalFirst
    ? source.externalUrl || source.internalUrl || ''
    : source.internalUrl || source.externalUrl || ''
}

function bytes(value) {
  if (!Number.isFinite(value)) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let index = 0
  let size = value
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index++ }
  return `${size.toFixed(index > 1 ? 1 : 0)} ${units[index]}`
}

function systemMetric(card) {
  const status = props.statuses[card.id]
  if (!status) return ''
  switch (card.system?.metric) {
    case 'cpu': return `使用率 ${status.cpuPercent ?? 0}%`
    case 'memory': return `${bytes(status.memoryUsed)} / ${bytes(status.memoryTotal)}`
    case 'network': return `接收 ${bytes(status.networkReceived)} · 发送 ${bytes(status.networkSent)}`
    case 'storage': return `总容量 ${bytes(status.diskTotal)}`
    default: return `CPU ${status.cpuPercent ?? 0}% · 内存 ${bytes(status.memoryUsed)}`
  }
}

function serviceMetric(card) {
  return props.statuses[card.id]?.summary || ''
}

function dockerMetric(card) {
  const status = props.statuses[card.id]
  if (!status || !status.online) return status?.summary || ''
  const cpu = Number.isFinite(status.cpuPercent) ? `${status.cpuPercent}%` : '--'
  const memory = Number.isFinite(status.memoryUsed)
    ? `${bytes(status.memoryUsed)}${status.memoryLimit ? ` / ${bytes(status.memoryLimit)}` : ''}`
    : '--'
  return `CPU ${cpu} · RAM ${memory}`
}

function dockerUptime(card) {
  const status = props.statuses[card.id]
  if (!status?.online) return ''
  const seconds = status.uptimeSeconds || 0
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor(seconds % 86400 / 3600)
  const minutes = Math.floor(seconds % 3600 / 60)
  if (days) return `运行 ${days} 天 ${hours} 小时`
  if (hours) return `运行 ${hours} 小时 ${minutes} 分钟`
  return `运行 ${minutes} 分钟`
}

function dockerActions(card) {
  const running = props.statuses[card.id]?.state === 'running'
  return running
    ? [
        { value: 'stop', title: '停止容器', icon: 'mdi-stop', color: 'error' },
        { value: 'restart', title: '重启容器', icon: 'mdi-restart', color: 'warning' }
      ]
    : [{ value: 'start', title: '启动容器', icon: 'mdi-play', color: 'success' }]
}

async function controlDocker(card, action) {
  if (!appState.auth.authenticated || dockerActionLoading.value[card.id]) return
  dockerActionLoading.value = { ...dockerActionLoading.value, [card.id]: action }
  actionError.value = ''
  try {
    appState.statuses[card.id] = await api('/api/admin/docker/containers/action', {
      method: 'POST',
      body: JSON.stringify({ containerId: card.docker?.containerId, action })
    })
  } catch (error) {
    actionError.value = error.message
  } finally {
    const next = { ...dockerActionLoading.value }
    delete next[card.id]
    dockerActionLoading.value = next
  }
}
</script>

<template>
  <section class="group-section" :class="{ cover }">
    <header v-if="!hideHeader" class="group-header">
      <v-icon :icon="group.icon" size="20" />
      <h2>{{ group.title }}</h2>
    </header>
    <div class="card-grid" :class="{ 'icon-grid': group.displayMode === 'icon', centered }">
      <div
        v-for="card in cards"
        :key="card.id"
        class="nav-card"
        :class="{
          'icon-only': group.displayMode === 'icon',
          'docker-card': card.type === 'docker',
          'not-clickable': card.type === 'system' || !cardUrl(card)
        }"
        :role="card.type !== 'system' && cardUrl(card) ? 'link' : undefined"
        :tabindex="card.type !== 'system' && cardUrl(card) ? 0 : undefined"
        @click="open(card)"
        @keydown.enter.self="open(card)"
      >
        <span class="card-icon">
          <img v-if="card.iconUrl" :src="appUrl(card.iconUrl)" alt="" />
          <v-icon v-else :icon="card.icon || 'mdi-web'" size="27" />
        </span>
        <span v-if="group.displayMode !== 'icon'" class="card-copy">
          <strong>{{ card.title }}</strong>
          <small>{{ card.remark || '打开服务' }}</small>
          <small v-if="card.type === 'system' && statuses[card.id]" class="metric">
            {{ systemMetric(card) }}
          </small>
          <small v-else-if="card.type === 'service' && serviceMetric(card)" class="metric">
            {{ serviceMetric(card) }}
          </small>
          <small v-else-if="card.type === 'docker' && statuses[card.id]" class="metric">
            {{ dockerMetric(card) }}
          </small>
          <small v-if="card.type === 'docker' && dockerUptime(card)" class="metric docker-uptime">
            {{ dockerUptime(card) }}
          </small>
        </span>
        <span v-if="statuses[card.id] && card.type !== 'system'" class="state-dot" :class="{ online: statuses[card.id].online }" :title="statuses[card.id].status" />
        <span v-if="card.type === 'docker' && appState.auth.authenticated && group.displayMode !== 'icon'" class="docker-actions" @click.stop @keydown.stop>
          <v-tooltip v-for="action in dockerActions(card)" :key="action.value" :text="action.title" location="top">
            <template #activator="{ props: tooltipProps }">
              <v-btn
                v-bind="tooltipProps"
                :icon="action.icon"
                :color="action.color"
                :loading="dockerActionLoading[card.id] === action.value"
                :disabled="Boolean(dockerActionLoading[card.id])"
                variant="text"
                size="x-small"
                :aria-label="action.title"
                @click.stop="controlDocker(card, action.value)"
              />
            </template>
          </v-tooltip>
        </span>
        <v-menu v-else-if="card.type === 'docker' && appState.auth.authenticated">
          <template #activator="{ props: menuProps }">
            <v-btn v-bind="menuProps" icon="mdi-dots-horizontal" variant="text" size="x-small" class="docker-menu" aria-label="容器操作" @click.stop />
          </template>
          <v-list density="compact" @click.stop>
            <v-list-item
              v-for="action in dockerActions(card)"
              :key="action.value"
              :prepend-icon="action.icon"
              :title="action.title"
              :disabled="Boolean(dockerActionLoading[card.id])"
              @click="controlDocker(card, action.value)"
            />
          </v-list>
        </v-menu>
        <v-tooltip v-if="group.displayMode === 'icon'" activator="parent" location="bottom">{{ card.title }}</v-tooltip>
      </div>
    </div>
    <v-snackbar :model-value="Boolean(actionError)" color="error" timeout="3500" @update:model-value="value => { if (!value) actionError = '' }">{{ actionError }}</v-snackbar>
  </section>
</template>

<style scoped>
.group-section { padding-block: 20px 26px; }
.group-section.cover { color: white; text-shadow: 0 1px 8px rgba(0,0,0,.32); }
.group-header { display: flex; align-items: center; gap: 9px; margin-bottom: 13px; }
.group-header h2 { margin: 0; font-size: 1.05rem; font-weight: 700; letter-spacing: 0; }
.card-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.card-grid.icon-grid { grid-template-columns: repeat(auto-fill, minmax(68px, 1fr)); }
.card-grid.centered { grid-template-columns: repeat(auto-fit, minmax(220px, 270px)); justify-content: center; }
.card-grid.centered.icon-grid { grid-template-columns: repeat(auto-fit, 68px); }
.nav-card {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
  height: 86px;
  gap: 13px;
  padding: 14px;
  border: 1px solid rgba(var(--v-theme-on-surface), .09);
  border-radius: 8px;
  background: rgba(var(--v-theme-surface), .72);
  color: rgb(var(--v-theme-on-surface));
  cursor: pointer;
  text-align: left;
  backdrop-filter: blur(14px);
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}
.cover .nav-card { border-color: rgba(255,255,255,.22); background: rgba(21,26,25,.5); color: white; }
.nav-card:hover { transform: translateY(-2px); border-color: rgba(var(--v-theme-primary), .28); box-shadow: 0 9px 26px rgba(17,24,20,.12); }
.nav-card:focus-visible { outline: 2px solid rgb(var(--v-theme-primary)); outline-offset: 2px; }
.nav-card.not-clickable { cursor: default; opacity: 1; }
.nav-card.not-clickable:hover { transform: none; border-color: rgba(var(--v-theme-on-surface), .09); box-shadow: none; }
.cover .nav-card.not-clickable:hover { border-color: rgba(255,255,255,.22); }
.nav-card.icon-only { justify-content: center; height: 68px; padding: 8px; }
.nav-card.docker-card:not(.icon-only) { height: 116px; }
.docker-card:not(.icon-only) .card-copy { padding-right: 58px; }
.card-icon { display: grid; flex: 0 0 46px; width: 46px; height: 46px; place-items: center; overflow: hidden; border: 1px solid rgba(255,255,255,.16); border-radius: 8px; background: rgba(var(--v-theme-secondary), .68); color: #1a211d; backdrop-filter: blur(8px); }
.card-icon img { width: 30px; height: 30px; object-fit: contain; }
.card-copy { display: grid; min-width: 0; gap: 3px; }
.card-copy strong, .card-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-copy strong { font-size: .94rem; letter-spacing: 0; }
.card-copy small { color: currentColor; opacity: .62; font-size: .76rem; }
.card-copy .metric { opacity: .82; color: rgb(var(--v-theme-info)); }
.cover .card-copy .metric { color: #e9ff70; }
.docker-uptime { color: currentColor !important; opacity: .58 !important; }
.docker-actions { position: absolute; right: 8px; bottom: 7px; display: flex; gap: 2px; }
.docker-menu { position: absolute; right: 1px; bottom: 1px; color: currentColor; }
.state-dot { position: absolute; top: 11px; right: 11px; width: 8px; height: 8px; border-radius: 50%; background: rgb(var(--v-theme-error)); }
.state-dot.online { background: rgb(var(--v-theme-success)); }
@media (max-width: 900px) { .card-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 520px) { .card-grid { grid-template-columns: 1fr; } .card-grid.icon-grid { grid-template-columns: repeat(4, 1fr); } }
</style>
