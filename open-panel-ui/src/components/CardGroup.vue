<script setup>
import { ref, watch } from 'vue'
import { api } from '../lib/api'
import { appUrl } from '../lib/paths'
import { appState } from '../stores/app'

const props = defineProps({
  group: Object,
  cards: Array,
  statuses: Object,
  cover: Boolean,
  hideHeader: Boolean,
  centered: Boolean,
  editing: Boolean,
  draggedCardId: String
})
const emit = defineEmits(['add-card', 'edit-card', 'remove-card', 'move-card', 'drag-start', 'drag-end', 'drop-card', 'drop-group'])
const dockerActionLoading = ref({})
const actionError = ref('')
const contextNotice = ref('')
const contextMenuOpen = ref(false)
const contextMenuCard = ref(null)
const contextMenuTarget = ref([0, 0])
const dropTargetCardId = ref('')
const dropPosition = ref('before')
const dropPreviewAtEnd = ref(false)

watch(() => props.draggedCardId, value => {
  if (!value) clearDropPreview()
})

function handleCardClick(card) {
  if (!props.editing) open(card)
}

function showContextMenu(event, card) {
  if (props.editing) return
  contextMenuOpen.value = false
  contextMenuCard.value = card
  contextMenuTarget.value = [event.clientX, event.clientY]
  requestAnimationFrame(() => { contextMenuOpen.value = true })
}

function startDrag(event, card) {
  if (!props.editing) return
  event.dataTransfer.effectAllowed = 'move'
  event.dataTransfer.setData('text/plain', card.id)
  emit('drag-start', card)
}

function clearDropPreview() {
  dropTargetCardId.value = ''
  dropPreviewAtEnd.value = false
}

function previewCardDrop(event, card) {
  if (!props.editing || !props.draggedCardId || props.draggedCardId === card.id) return
  const rect = event.currentTarget.getBoundingClientRect()
  const grid = event.currentTarget.parentElement
  const columnCount = getComputedStyle(grid).gridTemplateColumns.split(' ').length
  const after = columnCount === 1
    ? event.clientY > rect.top + rect.height / 2
    : event.clientX > rect.left + rect.width / 2
  dropTargetCardId.value = card.id
  dropPosition.value = after ? 'after' : 'before'
  dropPreviewAtEnd.value = false
}

function previewGroupEnd() {
  if (!props.editing || !props.draggedCardId) return
  dropTargetCardId.value = ''
  dropPreviewAtEnd.value = true
}

function leaveDropArea(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) clearDropPreview()
}

function dropOnCard(card, position = dropPosition.value) {
  emit('drop-card', { groupId: props.group.id, cardId: card.id, position })
  clearDropPreview()
}

function dropIntoGroup() {
  emit('drop-group', props.group.id)
  clearDropPreview()
}

function open(card) {
  const url = cardUrl(card)
  if (card.type === 'system' || !url) return
  openUrl(url, card.openTarget)
}

function openUrl(url, target) {
  if (!url) return
  contextMenuOpen.value = false
  if (target === 'self') window.location.href = url
  else window.open(url, '_blank', 'noopener,noreferrer')
}

function sourceUrl(card, network) {
  return card?.[card.type]?.[`${network}Url`] || ''
}

async function copyUrl(url) {
  if (!url) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(url)
    } else {
      const input = document.createElement('textarea')
      input.value = url
      input.style.position = 'fixed'
      input.style.opacity = '0'
      document.body.appendChild(input)
      input.select()
      document.execCommand('copy')
      input.remove()
    }
    contextNotice.value = '地址已复制'
  } catch {
    contextNotice.value = '复制失败，请手动复制'
  }
}

function editFromContextMenu() {
  contextMenuOpen.value = false
  emit('edit-card', contextMenuCard.value)
}

function removeFromContextMenu() {
  contextMenuOpen.value = false
  emit('remove-card', contextMenuCard.value)
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
  if (status.online === false) return status.status || '无法获取系统信息'
  switch (card.system?.metric) {
    case 'cpu': return `使用率 ${status.cpuPercent ?? 0}%`
    case 'memory': return `${bytes(status.memoryUsed)} / ${bytes(status.memoryTotal)}`
    case 'network': return `接收 ${bytes(status.networkReceived)} · 发送 ${bytes(status.networkSent)}`
    case 'storage': return `${bytes(status.diskUsed)} / ${bytes(status.diskTotal)}`
    default: return `CPU ${status.cpuPercent ?? 0}% · 内存 ${bytes(status.memoryUsed)}`
  }
}

function cardIcon(card) {
  return card.icon || 'mdi-web'
}

function serviceMetric(card) {
  return props.statuses[card.id]?.summary || ''
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
        { value: 'stop', title: '停止容器', icon: 'mdi-stop' },
        { value: 'restart', title: '重启容器', icon: 'mdi-restart' }
      ]
    : [{ value: 'start', title: '启动容器', icon: 'mdi-play' }]
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
  <section class="group-section" :class="{ cover, editing, 'group-disabled': editing && !group.enabled }">
    <header v-if="!hideHeader" class="group-header">
      <v-icon :icon="group.icon" size="20" />
      <h2>{{ group.title }}</h2>
      <v-btn
        v-if="appState.auth.authenticated"
        icon="mdi-plus"
        variant="text"
        size="x-small"
        class="group-add"
        :aria-label="`向${group.title}添加卡片`"
        :title="`向${group.title}添加卡片`"
        @click.stop="emit('add-card', group.id)"
      />
      <span v-if="editing && !group.enabled" class="hidden-label">已隐藏</span>
    </header>
    <div
      class="card-grid"
      :class="{ 'icon-grid': group.displayMode === 'icon', centered, 'editing-grid': editing }"
      @dragover.prevent.self="previewGroupEnd"
      @dragleave="leaveDropArea"
      @drop="dropIntoGroup"
    >
      <template v-for="card in cards" :key="card.id">
        <div
          v-if="dropTargetCardId === card.id && dropPosition === 'before'"
          class="card-drop-placeholder"
          :class="{ 'icon-only': group.displayMode === 'icon' }"
          aria-hidden="true"
          @dragover.prevent.stop
          @drop.stop="dropOnCard(card, 'before')"
        />
        <div
          class="nav-card"
          :class="{
            'icon-only': group.displayMode === 'icon',
            'docker-card': card.type === 'docker',
            'not-clickable': editing || card.type === 'system' || !cardUrl(card),
            'card-hidden': editing && !card.enabled,
            dragging: draggedCardId === card.id
          }"
          :role="!editing && card.type !== 'system' && cardUrl(card) ? 'link' : undefined"
          :tabindex="!editing && card.type !== 'system' && cardUrl(card) ? 0 : undefined"
          :draggable="editing"
          @click="handleCardClick(card)"
          @contextmenu.prevent.stop="showContextMenu($event, card)"
          @keydown.enter.self="handleCardClick(card)"
          @dragstart="startDrag($event, card)"
          @dragend="emit('drag-end')"
          @dragover.prevent.stop="previewCardDrop($event, card)"
          @drop.stop="dropOnCard(card)"
        >
        <span class="card-icon" :class="{ frameless: card.iconFrameless }">
          <img v-if="card.iconUrl" :src="appUrl(card.iconUrl)" alt="" />
          <v-icon v-else :icon="cardIcon(card)" size="27" />
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
          <small v-if="card.type === 'docker' && dockerUptime(card)" class="metric docker-uptime">
            {{ dockerUptime(card) }}
          </small>
        </span>
        <span v-if="!editing && statuses[card.id] && card.type !== 'system'" class="state-dot" :class="{ online: statuses[card.id].online }" :title="statuses[card.id].status" />
        <span v-if="!editing && card.type === 'docker' && appState.auth.authenticated && group.displayMode !== 'icon'" class="docker-actions" @click.stop @keydown.stop>
          <v-tooltip v-for="action in dockerActions(card)" :key="action.value" :text="action.title" location="top">
            <template #activator="{ props: tooltipProps }">
              <v-btn
                v-bind="tooltipProps"
                :icon="action.icon"
                :class="['docker-action-btn', `action-${action.value}`]"
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
        <v-menu v-else-if="!editing && card.type === 'docker' && appState.auth.authenticated">
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
        <v-btn
          v-if="editing"
          icon="mdi-close"
          variant="text"
          size="x-small"
          class="card-remove"
          :aria-label="`删除${card.title}`"
          :title="`删除${card.title}`"
          @click.stop="emit('remove-card', card)"
          @mousedown.stop
        />
        <v-tooltip v-if="group.displayMode === 'icon' && !editing" activator="parent" location="bottom">{{ card.title }}</v-tooltip>
        </div>
        <div
          v-if="dropTargetCardId === card.id && dropPosition === 'after'"
          class="card-drop-placeholder"
          :class="{ 'icon-only': group.displayMode === 'icon' }"
          aria-hidden="true"
          @dragover.prevent.stop
          @drop.stop="dropOnCard(card, 'after')"
        />
      </template>
      <div
        v-if="dropPreviewAtEnd"
        class="card-drop-placeholder"
        :class="{ 'icon-only': group.displayMode === 'icon' }"
        aria-hidden="true"
        @dragover.prevent.stop
        @drop.stop="dropIntoGroup"
      />
      <button v-if="editing" type="button" class="add-card-tile" @click="emit('add-card', group.id)" @dragover.prevent.stop="previewGroupEnd" @drop.stop="dropIntoGroup">
        <v-icon icon="mdi-plus" />
        <span>添加卡片</span>
      </button>
    </div>
    <v-menu
      v-model="contextMenuOpen"
      :target="contextMenuTarget"
      location-strategy="connected"
      location="bottom start"
      :offset="4"
      :min-width="contextMenuCard?.type === 'system' ? 196 : 238"
      max-width="278"
    >
      <v-list v-if="contextMenuCard" class="card-context-menu" density="compact" rounded="lg">
        <div class="context-menu-heading">
          <span class="context-menu-icon" :class="{ frameless: contextMenuCard.iconFrameless }">
            <img v-if="contextMenuCard.iconUrl" :src="appUrl(contextMenuCard.iconUrl)" alt="" />
            <v-icon v-else :icon="cardIcon(contextMenuCard)" size="21" />
          </span>
          <span>
            <strong>{{ contextMenuCard.title }}</strong>
            <small>{{ contextMenuCard.remark || '导航卡片' }}</small>
          </span>
        </div>
        <v-divider v-if="cardUrl(contextMenuCard)" />
        <v-list-subheader v-if="cardUrl(contextMenuCard)" class="context-menu-subheader">打开地址</v-list-subheader>
        <v-list-item
          v-if="cardUrl(contextMenuCard)"
          :prepend-icon="contextMenuCard.openTarget === 'self' ? 'mdi-open-in-app' : 'mdi-open-in-new'"
          title="默认地址"
          subtitle="按当前网络环境选择"
          @click="openUrl(cardUrl(contextMenuCard), contextMenuCard.openTarget)"
        >
          <template #append>
            <v-btn icon="mdi-content-copy" variant="text" size="small" aria-label="复制默认地址" title="复制默认地址" @click.stop="copyUrl(cardUrl(contextMenuCard))" />
          </template>
        </v-list-item>
        <v-list-item
          v-if="sourceUrl(contextMenuCard, 'internal')"
          prepend-icon="mdi-lan-connect"
          title="内网地址"
          :subtitle="sourceUrl(contextMenuCard, 'internal')"
          @click="openUrl(sourceUrl(contextMenuCard, 'internal'), contextMenuCard.openTarget)"
        >
          <template #append>
            <v-btn icon="mdi-content-copy" variant="text" size="small" aria-label="复制内网地址" title="复制内网地址" @click.stop="copyUrl(sourceUrl(contextMenuCard, 'internal'))" />
          </template>
        </v-list-item>
        <v-list-item
          v-if="sourceUrl(contextMenuCard, 'external')"
          prepend-icon="mdi-earth"
          title="公网地址"
          :subtitle="sourceUrl(contextMenuCard, 'external')"
          @click="openUrl(sourceUrl(contextMenuCard, 'external'), contextMenuCard.openTarget)"
        >
          <template #append>
            <v-btn icon="mdi-content-copy" variant="text" size="small" aria-label="复制公网地址" title="复制公网地址" @click.stop="copyUrl(sourceUrl(contextMenuCard, 'external'))" />
          </template>
        </v-list-item>
        <template v-if="appState.auth.authenticated">
          <v-divider class="context-menu-divider" />
          <v-list-item prepend-icon="mdi-pencil-outline" title="编辑卡片" @click="editFromContextMenu" />
          <v-list-item prepend-icon="mdi-delete-outline" title="删除卡片" class="context-menu-delete" @click="removeFromContextMenu" />
        </template>
      </v-list>
    </v-menu>
    <v-snackbar :model-value="Boolean(actionError)" color="error" timeout="3500" @update:model-value="value => { if (!value) actionError = '' }">{{ actionError }}</v-snackbar>
    <v-snackbar :model-value="Boolean(contextNotice)" timeout="2200" @update:model-value="value => { if (!value) contextNotice = '' }">{{ contextNotice }}</v-snackbar>
  </section>
</template>

<style scoped>
.group-section { padding-block: 20px 26px; }
.group-section.cover { color: white; text-shadow: 0 1px 8px rgba(0,0,0,.32); }
.group-header { display: flex; align-items: center; gap: 9px; margin-bottom: 13px; }
.group-header h2 { margin: 0; font-size: 1.05rem; font-weight: 700; letter-spacing: 0; }
.group-add { margin-left: auto; opacity: 0; pointer-events: none; transform: scale(.82); transition: opacity .16s ease, transform .16s ease; }
.group-section:hover .group-add, .group-add:focus-visible { opacity: 1; pointer-events: auto; transform: scale(1); }
.group-section.editing .group-add { opacity: 1; pointer-events: auto; transform: none; }
.group-disabled .group-header { opacity: .58; }
.hidden-label { padding: 2px 6px; border-radius: 4px; background: rgba(var(--v-theme-on-surface), .1); color: currentColor; opacity: .62; font-size: .7rem; }
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
  border-radius: var(--surface-radius, 8px);
  background: rgba(var(--v-theme-surface), var(--surface-opacity, .72));
  color: rgb(var(--v-theme-on-surface));
  cursor: pointer;
  text-align: left;
  user-select: none;
  -webkit-user-select: none;
  backdrop-filter: blur(14px);
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease;
}
.cover .nav-card { border-color: rgba(255,255,255,.22); background: rgba(21,26,25,var(--surface-opacity,.72)); color: white; }
.nav-card:hover { transform: translateY(-2px); border-color: rgba(var(--v-theme-primary), .28); box-shadow: 0 9px 26px rgba(17,24,20,.12); }
.nav-card[role="link"], .nav-card[role="link"] * { cursor: pointer; }
.nav-card:focus-visible { outline: 2px solid rgb(var(--v-theme-primary)); outline-offset: 2px; }
.nav-card.not-clickable { cursor: default; opacity: 1; }
.nav-card.not-clickable:hover { transform: none; border-color: rgba(var(--v-theme-on-surface), .09); box-shadow: none; }
.cover .nav-card.not-clickable:hover { border-color: rgba(255,255,255,.22); }
.nav-card.icon-only { justify-content: center; height: 68px; padding: 8px; }
.nav-card.card-hidden { opacity: .55; border-style: dashed; }
.nav-card.dragging { opacity: .35; }
.editing-grid .nav-card { cursor: grab; transform-origin: 50% 65%; animation: card-sort-wiggle .18s ease-in-out infinite alternate; }
.editing-grid .nav-card:nth-child(2n) { animation-direction: alternate-reverse; animation-delay: -.09s; }
.editing-grid .nav-card.dragging { animation: none; transform: none; }
.card-drop-placeholder { min-width: 0; height: 86px; border: 2px dashed rgba(var(--v-theme-primary), .62); border-radius: var(--surface-radius, 8px); background: rgba(var(--v-theme-primary), .1); box-shadow: inset 0 0 0 1px rgba(var(--v-theme-primary), .08); }
.cover .card-drop-placeholder { border-color: rgba(233,255,112,.68); background: rgba(233,255,112,.1); box-shadow: inset 0 0 0 1px rgba(233,255,112,.08); }
.card-drop-placeholder.icon-only { width: 68px; height: 68px; }
.card-remove { position: absolute; z-index: 3; top: 3px; right: 3px; color: rgba(var(--v-theme-on-surface), .58); background: rgba(var(--v-theme-surface), .7); }
.cover .card-remove { color: rgba(255,255,255,.86); background: rgba(18,22,21,.48); }
.card-remove:hover { color: rgb(var(--v-theme-error)); }
.add-card-tile { display: flex; align-items: center; justify-content: center; min-width: 0; height: 86px; gap: 7px; padding: 12px; border: 1px dashed rgba(var(--v-theme-on-surface), .28); border-radius: var(--surface-radius, 8px); background: rgba(var(--v-theme-surface), .38); color: currentColor; cursor: pointer; }
.icon-grid .add-card-tile { width: 68px; height: 68px; padding: 6px; }
.icon-grid .add-card-tile span { display: none; }
.cover .add-card-tile { border-color: rgba(255,255,255,.35); background: rgba(21,26,25,.38); }
.add-card-tile:hover { border-color: rgb(var(--v-theme-primary)); background: rgba(var(--v-theme-surface), .58); }
.card-icon { display: grid; flex: 0 0 46px; width: 46px; height: 46px; place-items: center; overflow: hidden; border: 1px solid rgba(255,255,255,.42); border-radius: 8px; background: rgba(255,255,255,.72); color: #1a211d; backdrop-filter: blur(8px); }
:global(.v-theme--dark) .card-icon { border-color: rgba(255,255,255,.12); background: rgba(255,255,255,.14); color: #f4f7f5; }
.cover .card-icon { border-color: rgba(255,255,255,.3); background: rgba(255,255,255,.72); color: #1a211d; }
.card-icon img { width: 30px; height: 30px; object-fit: contain; }
.card-icon.frameless { overflow: visible; border-color: transparent; background: transparent; color: currentColor; backdrop-filter: none; }
.card-icon.frameless img { width: 46px; height: 46px; border-radius: 22%; }
.card-copy { display: grid; min-width: 0; gap: 3px; }
.card-copy strong, .card-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-copy strong { font-size: .94rem; letter-spacing: 0; }
.card-copy small { color: currentColor; opacity: .62; font-size: .76rem; }
.card-copy .metric { opacity: .82; color: rgb(var(--v-theme-info)); }
.cover .card-copy .metric { color: #e9ff70; }
.docker-uptime { padding-right: 54px; color: currentColor !important; opacity: .58 !important; }
.docker-actions { position: absolute; z-index: 2; right: 5px; bottom: 4px; display: flex; gap: 0; opacity: 0; pointer-events: none; transition: opacity .16s ease; }
.docker-action-btn { color: rgba(var(--v-theme-on-surface), .62); transition: color .16s ease, background-color .16s ease; }
.cover .docker-action-btn { color: rgba(255,255,255,.72); }
.docker-action-btn.action-stop:hover { background: rgba(var(--v-theme-error), .12); color: rgb(var(--v-theme-error)); }
.docker-action-btn.action-restart:hover { background: rgba(var(--v-theme-warning), .12); color: rgb(var(--v-theme-warning)); }
.docker-action-btn.action-start:hover { background: rgba(var(--v-theme-success), .12); color: rgb(var(--v-theme-success)); }
.docker-menu { position: absolute; right: 3px; bottom: 3px; color: currentColor; opacity: 0; pointer-events: none; transition: opacity .16s ease; }
.docker-card:hover .docker-actions, .docker-card:focus-within .docker-actions,
.docker-card:hover .docker-menu, .docker-card:focus-within .docker-menu { opacity: 1; pointer-events: auto; }
.state-dot { position: absolute; top: 11px; right: 11px; width: 8px; height: 8px; border-radius: 50%; background: rgb(var(--v-theme-error)); }
.state-dot.online { background: rgb(var(--v-theme-success)); }
.card-context-menu { padding: 5px; border: 1px solid rgba(var(--v-theme-on-surface), .1); background: rgba(var(--v-theme-surface), .96); box-shadow: 0 12px 34px rgba(15,20,18,.18); user-select: none; -webkit-user-select: none; backdrop-filter: blur(18px); }
.context-menu-heading { display: flex; min-width: 0; align-items: center; gap: 9px; padding: 6px 8px 9px; }
.context-menu-heading > span:last-child { display: grid; min-width: 0; gap: 2px; }
.context-menu-heading strong, .context-menu-heading small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.context-menu-heading strong { font-size: .88rem; }
.context-menu-heading small { color: rgba(var(--v-theme-on-surface), .56); font-size: .7rem; }
.context-menu-icon { display: grid; width: 31px; height: 31px; flex: 0 0 31px; place-items: center; overflow: hidden; border: 1px solid rgba(var(--v-theme-on-surface), .08); border-radius: 6px; background: rgba(255,255,255,.7); color: #1a211d; }
:global(.v-theme--dark) .context-menu-icon { border-color: rgba(255,255,255,.1); background: rgba(255,255,255,.12); color: #f4f7f5; }
.context-menu-icon img { width: 22px; height: 22px; object-fit: contain; }
.context-menu-icon.frameless { overflow: visible; border-color: transparent; background: transparent; }
.context-menu-icon.frameless img { width: 31px; height: 31px; border-radius: 22%; }
.context-menu-subheader { min-height: 26px; padding-inline: 9px; font-size: .68rem; }
.card-context-menu :deep(.v-list-item) { min-height: 41px; margin-block: 1px; padding-inline: 9px; border-radius: 5px; }
.card-context-menu :deep(.v-list-item__prepend > .v-list-item__spacer) { width: 14px; }
.card-context-menu :deep(.v-list-item-title) { font-size: .84rem; }
.card-context-menu :deep(.v-list-item-subtitle) { max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: .68rem; }
.context-menu-divider { margin-block: 4px; }
.context-menu-delete { color: rgb(var(--v-theme-error)); }
@keyframes card-sort-wiggle {
  from { transform: rotate(-.48deg); }
  to { transform: rotate(.48deg); }
}
@media (prefers-reduced-motion: reduce) {
  .editing-grid .nav-card { animation: none; }
}
@media (max-width: 900px) { .card-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 520px) { .card-grid { grid-template-columns: 1fr; } .card-grid.icon-grid { grid-template-columns: repeat(4, 1fr); } }
@media (hover: none), (pointer: coarse) {
  .group-add { opacity: 1; pointer-events: auto; transform: none; }
  .docker-actions, .docker-menu { opacity: 1; pointer-events: auto; }
}
</style>
