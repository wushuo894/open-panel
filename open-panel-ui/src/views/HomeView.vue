<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import CardEditorDialog from '../components/CardEditorDialog.vue'
import CardGroup from '../components/CardGroup.vue'
import CornerControls from '../components/CornerControls.vue'
import SearchBar from '../components/SearchBar.vue'
import { api } from '../lib/api'
import { appUrl } from '../lib/paths'
import { appState, loadAuth, loadPanel, loadStatuses } from '../stores/app'

const router = useRouter()
const theme = useTheme()
const now = ref(new Date())
const wallpaperIndex = ref(0)
const editing = ref(false)
const editLoading = ref(false)
const saving = ref(false)
const editConfig = ref(null)
const editorOpen = ref(false)
const editorCard = ref(null)
const originalGroupId = ref('')
const draggedCardId = ref('')
const pendingRemoval = ref(null)
const notice = ref('')
const editError = ref('')
let clockTimer
let wallpaperTimer

const panel = computed(() => editing.value ? editConfig.value : appState.panel)
const coverMode = computed(() => panel.value?.page?.mode === 'cover')
const wallpapers = computed(() => (panel.value?.page?.cover?.wallpapers?.filter(Boolean) || []).map(appUrl))
const wallpaper = computed(() => wallpapers.value[wallpaperIndex.value] || appUrl(panel.value?.site?.background || ''))
const listBackground = computed(() => appUrl(panel.value?.site?.background || '') || wallpaper.value)
const pageBackground = computed(() => coverMode.value ? wallpaper.value : listBackground.value)
const groups = computed(() => [...(panel.value?.groups || [])].sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0)))
const coverGroupId = computed(() => coverMode.value ? panel.value?.page?.cover?.groupIds?.[0] || '' : '')
const coverGroup = computed(() => groups.value.find(group => group.id === coverGroupId.value))
const bodyGroups = computed(() => groups.value.filter(group => group.id !== coverGroupId.value))
const timeText = computed(() => {
  const withSeconds = panel.value?.page?.banner?.showSeconds
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: withSeconds ? '2-digit' : undefined, hour12: false }).format(now.value)
})
const dateText = computed(() => `${now.value.getMonth() + 1}-${now.value.getDate()}`)
const weekdayText = computed(() => new Intl.DateTimeFormat('zh-CN', {
  weekday: 'long'
}).format(now.value))

function cardsFor(group) {
  return (panel.value?.cards || [])
    .filter(card => card.groupId === group.id)
    .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
}

function uuid() {
  if (typeof globalThis.crypto?.randomUUID === 'function') return globalThis.crypto.randomUUID()
  const bytes = new Uint8Array(16)
  if (typeof globalThis.crypto?.getRandomValues === 'function') globalThis.crypto.getRandomValues(bytes)
  else for (let index = 0; index < bytes.length; index++) bytes[index] = Math.floor(Math.random() * 256)
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0'))
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10).join('')}`
}

async function startEditing() {
  editLoading.value = true
  editError.value = ''
  try {
    editConfig.value = await api('/api/admin/config')
    editing.value = true
  } catch (error) {
    editError.value = error.message
  } finally {
    editLoading.value = false
  }
}

function cancelEditing() {
  editing.value = false
  editConfig.value = null
  editorOpen.value = false
  pendingRemoval.value = null
  draggedCardId.value = ''
}

async function saveEditing() {
  saving.value = true
  editError.value = ''
  try {
    normalizeAllCardSort()
    await api('/api/admin/config', { method: 'PUT', body: JSON.stringify(editConfig.value) })
    editing.value = false
    editConfig.value = null
    await loadPanel()
    loadStatuses()
    notice.value = '首页卡片已保存'
  } catch (error) {
    editError.value = error.message
  } finally {
    saving.value = false
  }
}

function newCard(groupId) {
  originalGroupId.value = ''
  editorCard.value = {
    id: uuid(), groupId, type: 'custom', title: '新卡片', remark: '', icon: 'mdi-web', iconUrl: '', enabled: true,
    sort: cardsFor({ id: groupId }).length, openTarget: 'new',
    custom: { internalUrl: '', externalUrl: '' },
    system: { metric: 'overview', storagePath: '.' },
    service: { serviceType: 'generic', internalUrl: '', externalUrl: '', statusUrl: '', token: '' },
    docker: { containerId: '', internalUrl: '', externalUrl: '' }
  }
  editorOpen.value = true
}

function editCard(card) {
  originalGroupId.value = card.groupId
  editorCard.value = JSON.parse(JSON.stringify(card))
  editorOpen.value = true
}

function commitCard(card) {
  const cards = editConfig.value.cards
  const index = cards.findIndex(item => item.id === card.id)
  if (originalGroupId.value && originalGroupId.value !== card.groupId) {
    card.sort = cardsFor({ id: card.groupId }).filter(item => item.id !== card.id).length
  }
  if (index >= 0) cards.splice(index, 1, card)
  else cards.push(card)
  if (originalGroupId.value) normalizeCardSort(originalGroupId.value)
  normalizeCardSort(card.groupId)
  editorCard.value = null
}

function requestRemoveCard(card) {
  pendingRemoval.value = card
}

function removeCard() {
  const card = pendingRemoval.value
  if (!card) return
  editConfig.value.cards = editConfig.value.cards.filter(item => item.id !== card.id)
  normalizeCardSort(card.groupId)
  pendingRemoval.value = null
}

function normalizeCardSort(groupId) {
  cardsFor({ id: groupId }).forEach((card, index) => { card.sort = index })
}

function normalizeAllCardSort() {
  groups.value.forEach(group => normalizeCardSort(group.id))
}

function moveCard({ card, direction }) {
  const ordered = cardsFor({ id: card.groupId })
  const index = ordered.findIndex(item => item.id === card.id)
  const target = index + direction
  if (index < 0 || target < 0 || target >= ordered.length) return
  ;[ordered[index], ordered[target]] = [ordered[target], ordered[index]]
  ordered.forEach((item, sort) => { item.sort = sort })
}

function startCardDrag(card) {
  draggedCardId.value = card.id
}

function dropCard({ groupId, cardId, position = 'before' }) {
  const dragged = editConfig.value?.cards.find(card => card.id === draggedCardId.value)
  if (!dragged || dragged.id === cardId) return endCardDrag()
  const sourceGroupId = dragged.groupId
  const ordered = cardsFor({ id: groupId }).filter(card => card.id !== dragged.id)
  const targetIndex = ordered.findIndex(card => card.id === cardId)
  dragged.groupId = groupId
  const insertAt = targetIndex < 0 ? ordered.length : targetIndex + (position === 'after' ? 1 : 0)
  ordered.splice(insertAt, 0, dragged)
  ordered.forEach((card, index) => { card.sort = index })
  if (sourceGroupId !== groupId) normalizeCardSort(sourceGroupId)
  endCardDrag()
}

function dropCardIntoGroup(groupId) {
  const dragged = editConfig.value?.cards.find(card => card.id === draggedCardId.value)
  if (!dragged) return
  const sourceGroupId = dragged.groupId
  const ordered = cardsFor({ id: groupId }).filter(card => card.id !== dragged.id)
  dragged.groupId = groupId
  ordered.push(dragged)
  ordered.forEach((card, index) => { card.sort = index })
  if (sourceGroupId !== groupId) normalizeCardSort(sourceGroupId)
  endCardDrag()
}

function endCardDrag() {
  draggedCardId.value = ''
}

function scrollToNavigation() {
  document.getElementById('navigation')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function applyTheme() {
  const configured = panel.value?.site?.theme || 'system'
  const dark = configured === 'dark' || (configured === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  theme.global.name.value = dark ? 'openPanelDark' : 'openPanelLight'
}

function startWallpaperRotation() {
  clearInterval(wallpaperTimer)
  if (wallpapers.value.length < 2) return
  wallpaperTimer = setInterval(() => {
    wallpaperIndex.value = (wallpaperIndex.value + 1) % wallpapers.value.length
  }, Math.max(5, panel.value.page.cover.intervalSeconds || 30) * 1000)
}

onMounted(async () => {
  clockTimer = setInterval(() => { now.value = new Date() }, 1000)
  try {
    const auth = await loadAuth()
    if (!auth.anonymousAccess && !auth.authenticated) return router.replace('/login')
    await loadPanel()
    applyTheme()
    startWallpaperRotation()
    loadStatuses()
  } catch (error) {
    appState.error = error.message
  }
})
watch(panel, () => { if (panel.value) { applyTheme(); startWallpaperRotation() } })
onBeforeUnmount(() => { clearInterval(clockTimer); clearInterval(wallpaperTimer) })
</script>

<template>
  <main
    class="page-shell"
    :class="{ 'wallpaper-page': panel }"
    :style="panel ? { '--wallpaper': `url(${pageBackground})`, '--overlay': panel.site.backgroundOverlay } : undefined"
  >
    <v-progress-linear v-if="appState.loading" indeterminate color="secondary" class="loading" />
    <CornerControls
      v-if="panel"
      :hover-only="panel.site.cornerControlsHoverOnly"
      :on-cover="coverMode"
      :editing="editing"
      :edit-loading="editLoading"
      :saving="saving"
      @edit="startEditing"
      @save="saveEditing"
      @cancel="cancelEditing"
    />

    <section v-if="panel && coverMode" class="cover-panel" :style="{ '--wallpaper': `url(${wallpaper})`, '--overlay': panel.site.backgroundOverlay }">
      <div class="cover-content content-width">
        <div v-if="panel.page.banner.visible" class="banner">
          <div class="banner-main">
            <h1 v-if="panel.page.banner.showTitle">{{ panel.page.banner.title }}</h1>
            <span
              v-if="panel.page.banner.showTitle && (panel.page.banner.showTime || panel.page.banner.showDate || panel.page.banner.showWeekday)"
              class="banner-divider"
              aria-hidden="true"
            />
            <div v-if="panel.page.banner.showTime || panel.page.banner.showDate || panel.page.banner.showWeekday" class="banner-clock">
              <time v-if="panel.page.banner.showTime">{{ timeText }}</time>
              <div v-if="panel.page.banner.showDate || panel.page.banner.showWeekday" class="banner-date">
                <span v-if="panel.page.banner.showDate">{{ dateText }}</span>
                <span v-if="panel.page.banner.showWeekday">{{ weekdayText }}</span>
              </div>
            </div>
          </div>
          <p v-if="panel.page.banner.showQuote" class="banner-quote">{{ panel.page.banner.quote }}</p>
        </div>
        <SearchBar :engines="panel.searchEngines" glass />
        <div v-if="coverGroup" class="cover-groups">
          <CardGroup
            :group="coverGroup"
            :cards="cardsFor(coverGroup)"
            :statuses="appState.statuses"
            :editing="editing"
            :dragged-card-id="draggedCardId"
            cover
            hide-header
            centered
            @add-card="newCard"
            @edit-card="editCard"
            @remove-card="requestRemoveCard"
            @move-card="moveCard"
            @drag-start="startCardDrag"
            @drag-end="endCardDrag"
            @drop-card="dropCard"
            @drop-group="dropCardIntoGroup"
          />
        </div>
      </div>
      <button type="button" class="scroll-hint" aria-label="查看导航" @click="scrollToNavigation">
        <v-icon icon="mdi-chevron-down" />
      </button>
    </section>

    <section v-if="panel" id="navigation" class="navigation-band" :class="{ 'list-mode': !coverMode }">
      <div class="content-width">
        <header v-if="!coverMode" class="list-header">
          <div class="brand-lockup">
            <img :src="appUrl(panel.site.icon || 'icons/icon.svg')" alt="" />
            <strong>{{ panel.site.title }}</strong>
          </div>
          <div v-if="panel.page.banner.visible" class="banner list-banner">
            <div class="banner-main">
              <h1 v-if="panel.page.banner.showTitle">{{ panel.page.banner.title }}</h1>
              <span
                v-if="panel.page.banner.showTitle && (panel.page.banner.showTime || panel.page.banner.showDate || panel.page.banner.showWeekday)"
                class="banner-divider"
                aria-hidden="true"
              />
              <div v-if="panel.page.banner.showTime || panel.page.banner.showDate || panel.page.banner.showWeekday" class="banner-clock">
                <time v-if="panel.page.banner.showTime">{{ timeText }}</time>
                <div v-if="panel.page.banner.showDate || panel.page.banner.showWeekday" class="banner-date">
                  <span v-if="panel.page.banner.showDate">{{ dateText }}</span>
                  <span v-if="panel.page.banner.showWeekday">{{ weekdayText }}</span>
                </div>
              </div>
            </div>
            <p v-if="panel.page.banner.showQuote" class="banner-quote">{{ panel.page.banner.quote }}</p>
          </div>
          <SearchBar :engines="panel.searchEngines" />
        </header>
        <CardGroup
          v-for="group in (coverMode ? bodyGroups : groups)"
          :key="group.id"
          :group="group"
          :cards="cardsFor(group)"
          :statuses="appState.statuses"
          :editing="editing"
          :dragged-card-id="draggedCardId"
          @add-card="newCard"
          @edit-card="editCard"
          @remove-card="requestRemoveCard"
          @move-card="moveCard"
          @drag-start="startCardDrag"
          @drag-end="endCardDrag"
          @drop-card="dropCard"
          @drop-group="dropCardIntoGroup"
        />
        <div v-if="!(coverMode ? bodyGroups : groups).length" class="empty-state">
          <v-icon icon="mdi-view-grid-plus-outline" size="34" />
          <p>暂无导航分组</p>
        </div>
      </div>
    </section>

    <footer v-if="panel?.page?.footer?.visible" class="footer">
      <div v-for="(line, index) in panel.page.footer.lines.slice(0, 4)" :key="index">{{ line }}</div>
    </footer>

    <div v-if="appState.error" class="error-state">
      <v-icon icon="mdi-lan-disconnect" size="36" />
      <strong>无法载入导航页</strong>
      <span>{{ appState.error }}</span>
      <v-btn color="secondary" @click="loadPanel">重试</v-btn>
    </div>

    <CardEditorDialog v-model="editorOpen" :card="editorCard" :groups="groups" @save="commitCard" />

    <v-dialog :model-value="Boolean(pendingRemoval)" max-width="420" @update:model-value="value => { if (!value) pendingRemoval = null }">
      <v-card>
        <v-card-title>删除卡片</v-card-title>
        <v-card-text>确定删除“{{ pendingRemoval?.title }}”吗？</v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="pendingRemoval = null">取消</v-btn>
          <v-btn color="error" @click="removeCard">删除</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-snackbar :model-value="Boolean(notice)" color="success" timeout="2600" @update:model-value="value => { if (!value) notice = '' }">{{ notice }}</v-snackbar>
    <v-snackbar :model-value="Boolean(editError)" color="error" timeout="4200" @update:model-value="value => { if (!value) editError = '' }">{{ editError }}</v-snackbar>
  </main>
</template>

<style scoped>
.loading { position: fixed; z-index: 100; top: 0; }
.cover-panel { position: relative; min-height: 100svh; display: flex; align-items: center; overflow: hidden; color: white; background: transparent; }
.cover-content { position: relative; z-index: 1; display: grid; gap: 27px; padding-block: 88px 76px; }
.banner { display: grid; justify-items: center; gap: 17px; max-width: 100%; text-align: center; text-shadow: 0 3px 22px rgba(0,0,0,.46); }
.banner-main { display: flex; max-width: 100%; align-items: center; justify-content: center; }
.banner h1 { max-width: 720px; margin: 0; overflow-wrap: anywhere; color: white; font-size: 5.5rem; line-height: .92; font-weight: 760; letter-spacing: 0; }
.banner-divider { width: 2px; height: 62px; flex: 0 0 2px; margin-inline: 28px; background: rgba(255,255,255,.76); box-shadow: 0 2px 12px rgba(0,0,0,.22); }
.banner-clock { display: grid; flex: 0 0 auto; justify-items: center; gap: 8px; }
.banner time { display: block; color: white; font-size: 3.35rem; line-height: .9; font-weight: 720; font-variant-numeric: tabular-nums; letter-spacing: 0; }
.banner-date { display: flex; align-items: center; justify-content: center; gap: 8px; color: white; font-size: 1.12rem; line-height: 1.2; font-weight: 650; }
.banner-quote { max-width: 58ch; margin: 0; color: rgba(255,255,255,.84); font-size: .96rem; line-height: 1.5; }
.cover-groups { max-height: 310px; overflow: auto; scrollbar-width: thin; }
.scroll-hint { position: absolute; z-index: 2; bottom: 0; left: 50%; display: grid; width: 96px; height: 58px; padding: 0; border: 0; place-items: center; background: transparent; color: white; cursor: pointer; transform: translateX(-50%); opacity: 0; transition: opacity .18s ease, transform .18s ease; }
.scroll-hint:focus-visible { opacity: .82; transform: translateX(-50%) translateY(-3px); }
.navigation-band { min-height: 340px; padding-block: 26px 48px; background: rgb(var(--v-theme-background)); }
.navigation-band.list-mode { min-height: 0; padding: 24px 0 0; }
.page-shell.wallpaper-page { position: relative; isolation: isolate; background: transparent; color: white; }
.page-shell.wallpaper-page::before { content: ''; position: fixed; z-index: -1; inset: 0; background-image: linear-gradient(rgba(8,12,11,var(--overlay)), rgba(8,12,11,var(--overlay))), var(--wallpaper); background-position: center; background-size: cover; pointer-events: none; transform: translateZ(0); }
.wallpaper-page .navigation-band { background: transparent; }
.wallpaper-page .brand-lockup, .wallpaper-page .list-banner { text-shadow: 0 2px 16px rgba(0,0,0,.46); }
.wallpaper-page .list-banner p { color: rgba(255,255,255,.76); }
.wallpaper-page .footer { border-top-color: rgba(255,255,255,.14); background: rgba(12,16,15,.72); color: rgba(255,255,255,.72); backdrop-filter: blur(12px); }
.list-header { display: grid; gap: 26px; padding-block: 16px 34px; }
.brand-lockup { display: flex; align-items: center; gap: 11px; font-size: 1.05rem; }
.brand-lockup img { width: 34px; height: 34px; border-radius: 8px; }
.list-banner { gap: 13px; }
.list-banner h1 { max-width: 520px; font-size: 3.6rem; }
.list-banner .banner-divider { height: 44px; margin-inline: 22px; }
.list-banner time { font-size: 2.45rem; }
.list-banner .banner-date { font-size: .92rem; }
.list-banner .banner-quote { color: rgba(255,255,255,.76); }
.footer { padding: 20px; border-top: 1px solid rgba(var(--v-theme-on-surface), .08); background: rgb(var(--v-theme-background)); color: rgba(var(--v-theme-on-surface), .52); text-align: center; font-size: .78rem; line-height: 1.65; }
.empty-state, .error-state { display: grid; place-items: center; gap: 9px; min-height: 260px; color: rgba(var(--v-theme-on-surface), .58); text-align: center; }
.error-state { position: fixed; z-index: 20; inset: 0; background: rgb(var(--v-theme-background)); }
.error-state strong { color: rgb(var(--v-theme-on-surface)); }
@media (hover: hover) and (pointer: fine) {
  .scroll-hint:hover { opacity: .82; transform: translateX(-50%) translateY(-3px); }
}
@media (hover: none), (pointer: coarse) {
  .scroll-hint { opacity: .72; }
}
@media (max-width: 600px) {
  .cover-content { gap: 20px; padding-block: 76px 58px; }
  .cover-groups { max-height: 280px; }
  .banner { gap: 14px; }
  .banner-main { flex-direction: column; gap: 12px; }
  .banner h1, .list-banner h1 { max-width: min(100%, 12ch); font-size: 2.8rem; line-height: 1; }
  .banner-divider, .list-banner .banner-divider { width: 72px; height: 1px; flex-basis: 1px; margin: 0; }
  .banner time, .list-banner time { font-size: 2.55rem; }
  .banner-date, .list-banner .banner-date { font-size: .9rem; }
  .banner-quote { max-width: 30ch; font-size: .9rem; }
}
</style>
