<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../lib/api'
import { appUrl } from '../lib/paths'
import MdiIconPicker from './MdiIconPicker.vue'

const props = defineProps({
  modelValue: Boolean,
  card: Object,
  groups: Array
})
const emit = defineEmits(['update:modelValue', 'save'])

const draft = ref(null)
const uploadingIcon = ref(false)
const loadingMetadata = ref(false)
const dockerContainers = ref([])
const error = ref('')
const showServiceToken = ref(false)
const iconSource = ref('mdi')
const remoteIconUrl = ref('')
const uploadedIconUrl = ref('')

const cardTypes = [
  { title: '自定义链接', value: 'custom' },
  { title: '系统信息', value: 'system' },
  { title: '软件服务', value: 'service' },
  { title: 'Docker 容器', value: 'docker' }
]
const systemMetrics = [
  { title: '综合信息', value: 'overview', cardTitle: '系统信息' },
  { title: 'CPU', value: 'cpu', cardTitle: 'CPU' },
  { title: '内存', value: 'memory', cardTitle: '内存' },
  { title: '存储', value: 'storage', cardTitle: '存储' },
  { title: '网络', value: 'network', cardTitle: '网络' }
]
const serviceTypes = [
  { title: 'Emby', value: 'emby' },
  { title: 'ani-rss', value: 'ani-rss' },
  { title: 'qBittorrent', value: 'qbit' },
  { title: 'OpenList', value: 'openlist' },
  { title: '通用 Web 服务', value: 'generic' }
]
const serviceDefaults = {
  emby: ['Emby', 'mdi-play-circle-outline', '媒体服务器'],
  'ani-rss': ['ani-rss', 'mdi-rss', '自动追番与下载'],
  qbit: ['qBittorrent', 'mdi-download-network-outline', '下载服务'],
  openlist: ['OpenList', 'mdi-folder-network-outline', '聚合存储与文件管理'],
  generic: ['Web 服务', 'mdi-application-outline', '自托管 Web 服务']
}
const serviceAuthHints = {
  emby: '填写 Emby API Key 后可显示版本、活跃播放和转码数量。',
  'ani-rss': '填写 ani-rss 设置页生成的 API Key。',
  qbit: '填写 qBittorrent 生成的 qbt_ 开头 API Key。',
  openlist: '填写 OpenList API Token，可显示存储总容量和存储数量。',
  generic: '通用服务使用状态检测 URL 探测 HTTP 状态。'
}
const currentServiceAuthHint = computed(() => serviceAuthHints[draft.value?.service?.serviceType] || serviceAuthHints.generic)

watch(() => props.card, value => {
  if (!value) {
    draft.value = null
    return
  }
  draft.value = normalizeCard(JSON.parse(JSON.stringify(value)))
  initializeIconSource(draft.value.iconUrl)
  error.value = ''
  showServiceToken.value = false
}, { immediate: true })

watch(() => props.modelValue, value => {
  if (value) loadDockerContainers()
})

function normalizeCard(card) {
  card.custom ||= { internalUrl: '', externalUrl: '' }
  card.system ||= { metric: 'overview', storagePath: '.' }
  card.system.storagePath ||= '.'
  card.service ||= { serviceType: 'generic', internalUrl: '', externalUrl: '', statusUrl: '', token: '' }
  card.docker ||= { containerId: '', internalUrl: '', externalUrl: '' }
  return card
}

function initializeIconSource(iconUrl) {
  remoteIconUrl.value = ''
  uploadedIconUrl.value = ''
  if (!iconUrl) {
    iconSource.value = 'mdi'
  } else if (/^\/?assets\/uploads\//.test(iconUrl)) {
    iconSource.value = 'upload'
    uploadedIconUrl.value = iconUrl
  } else {
    iconSource.value = 'url'
    remoteIconUrl.value = iconUrl
  }
}

function changeIconSource(source) {
  iconSource.value = source
  if (source === 'mdi') draft.value.iconUrl = ''
  else if (source === 'url') draft.value.iconUrl = remoteIconUrl.value
  else draft.value.iconUrl = uploadedIconUrl.value
}

function setRemoteIconUrl(value) {
  remoteIconUrl.value = value || ''
  if (iconSource.value === 'url') draft.value.iconUrl = remoteIconUrl.value
}

function close() {
  emit('update:modelValue', false)
}

function submit() {
  if (!draft.value?.title?.trim()) {
    error.value = '请填写卡片标题'
    return
  }
  if (!draft.value.groupId) {
    error.value = '请选择所属分组'
    return
  }
  draft.value.title = draft.value.title.trim()
  emit('save', JSON.parse(JSON.stringify(draft.value)))
  close()
}

function applyCardTypeDefaults(type) {
  draft.value.type = type
  if (type === 'system') {
    Object.assign(draft.value, { icon: 'mdi-server-outline', remark: 'CPU、内存、存储与网络' })
    changeSystemMetric(draft.value.system.metric || 'overview')
  } else if (type === 'docker') {
    Object.assign(draft.value, { title: 'Docker 容器', icon: 'mdi-docker', remark: '容器运行状态' })
    loadDockerContainers()
  } else if (type === 'service') {
    applyServiceDefaults(draft.value.service.serviceType || 'generic')
  } else {
    Object.assign(draft.value, { title: '自定义链接', icon: 'mdi-web', remark: '打开常用网站或服务' })
  }
}

function changeCardType(type) {
  const previousType = draft.value.type
  const previousSource = draft.value[previousType]
  const internalUrl = previousSource?.internalUrl || ''
  const externalUrl = previousSource?.externalUrl || ''
  applyCardTypeDefaults(type)
  const nextSource = draft.value[type]
  if (!nextSource) return
  if (!nextSource.internalUrl && internalUrl) nextSource.internalUrl = internalUrl
  if (!nextSource.externalUrl && externalUrl) nextSource.externalUrl = externalUrl
}

function applyServiceDefaults(type) {
  draft.value.service.serviceType = type
  const [title, icon, remark] = serviceDefaults[type] || serviceDefaults.generic
  Object.assign(draft.value, { title, icon, remark })
}

function changeSystemMetric(metric) {
  draft.value.system.metric = metric
  const selectedMetric = systemMetrics.find(item => item.value === metric)
  if (selectedMetric) draft.value.title = selectedMetric.cardTitle
}

async function autoFillCardFromLink() {
  const source = draft.value[draft.value.type]
  const url = source?.internalUrl?.trim() || source?.externalUrl?.trim()
  if (!url) {
    error.value = '请先填写内网或公网 URL'
    return
  }
  loadingMetadata.value = true
  error.value = ''
  try {
    const metadata = await api('/api/admin/link-metadata', {
      method: 'POST',
      body: JSON.stringify({ url })
    })
    if (metadata.title) draft.value.title = metadata.title
    if (metadata.description) draft.value.remark = metadata.description
    if (metadata.iconUrl) {
      remoteIconUrl.value = metadata.iconUrl
      iconSource.value = 'url'
      draft.value.iconUrl = metadata.iconUrl
    }
  } catch (exception) {
    error.value = exception.message
  } finally {
    loadingMetadata.value = false
  }
}

async function loadDockerContainers() {
  try {
    dockerContainers.value = await api('/api/admin/docker/containers')
  } catch {
    dockerContainers.value = []
  }
}

function changeDockerContainer(value) {
  const selectedValue = typeof value === 'object' ? value?.id : value
  const container = dockerContainers.value.find(item =>
    item.id === selectedValue || item.containerId === selectedValue || item.name === selectedValue
  )
  if (container?.name) draft.value.title = container.name
}

async function uploadIcon(value) {
  const file = Array.isArray(value) ? value[0] : value
  if (!file) return
  uploadingIcon.value = true
  error.value = ''
  const body = new FormData()
  body.append('file', file)
  try {
    const result = await api('/api/admin/assets/icon', { method: 'POST', body })
    uploadedIconUrl.value = result.url
    iconSource.value = 'upload'
    draft.value.iconUrl = result.url
  } catch (exception) {
    error.value = exception.message
  } finally {
    uploadingIcon.value = false
  }
}
</script>

<template>
  <v-dialog :model-value="modelValue" max-width="720" scrollable persistent @update:model-value="value => emit('update:modelValue', value)">
    <v-card v-if="draft">
      <v-card-title>卡片设置</v-card-title>
      <v-card-text class="dialog-form">
        <v-alert v-if="error" type="error" variant="tonal" density="compact" closable @click:close="error = ''">{{ error }}</v-alert>
        <div class="card-option-fields">
          <v-select v-model="draft.groupId" label="所属分组" :items="groups" item-title="title" item-value="id" />
          <v-select :model-value="draft.type" label="卡片类型" :items="cardTypes" @update:model-value="changeCardType" />
          <v-select v-model="draft.openTarget" label="打开方式" :items="[{title:'新窗口',value:'new'},{title:'当前窗口',value:'self'}]" />
        </div>
        <div class="form-grid card-content-fields">
          <v-text-field v-model="draft.title" label="标题" />
          <v-text-field v-model="draft.remark" label="备注" />
        </div>

        <section class="icon-section">
          <div class="icon-section-head">
            <strong>图标来源</strong>
            <span>选择一种图标设置方式</span>
          </div>
          <v-tabs
            :model-value="iconSource"
            color="primary"
            density="compact"
            height="42"
            grow
            class="icon-source-tabs"
            @update:model-value="changeIconSource"
          >
            <v-tab value="mdi" prepend-icon="mdi-shape-outline">图标库</v-tab>
            <v-tab value="url" prepend-icon="mdi-link-variant">图片链接</v-tab>
            <v-tab value="upload" prepend-icon="mdi-upload-outline">本地上传</v-tab>
          </v-tabs>
          <div class="icon-editor-row">
            <span class="icon-preview">
              <v-icon v-if="iconSource === 'mdi' || !draft.iconUrl" :icon="iconSource === 'mdi' ? (draft.icon || 'mdi-web') : 'mdi-image-outline'" size="32" />
              <img v-else :src="appUrl(draft.iconUrl)" alt="当前卡片图标" />
            </span>
            <MdiIconPicker v-if="iconSource === 'mdi'" v-model="draft.icon" />
            <v-text-field
              v-else-if="iconSource === 'url'"
              :model-value="remoteIconUrl"
              label="图片 URL"
              prepend-inner-icon="mdi-link-variant"
              @update:model-value="setRemoteIconUrl"
            />
            <v-file-input
              v-else
              label="上传卡片图标"
              accept="image/png,image/jpeg,image/gif,image/webp"
              prepend-icon="mdi-image-plus-outline"
              :loading="uploadingIcon"
              hint="PNG、JPEG、GIF 或 WebP，最大 2 MB"
              persistent-hint
              @update:model-value="uploadIcon"
            />
          </div>
        </section>
        <v-switch v-model="draft.enabled" label="在首页显示" color="primary" />

        <template v-if="draft.type === 'custom'">
          <div class="link-fields-with-action">
            <v-text-field v-model="draft.custom.internalUrl" label="内网 URL" />
            <v-text-field v-model="draft.custom.externalUrl" label="公网 URL" />
            <v-btn prepend-icon="mdi-auto-fix" variant="outlined" :loading="loadingMetadata" @click="autoFillCardFromLink">从链接自动补全</v-btn>
          </div>
        </template>
        <template v-else-if="draft.type === 'system'">
          <v-select v-model="draft.system.metric" label="系统信息" :items="systemMetrics" @update:model-value="changeSystemMetric" />
          <v-text-field
            v-if="draft.system.metric === 'storage'"
            v-model="draft.system.storagePath"
            label="文件夹路径"
            placeholder="/downloads"
            hint="显示该路径所在分区的已用容量和总容量；Docker 部署时填写容器内路径"
            persistent-hint
          />
        </template>
        <template v-else-if="draft.type === 'service'">
          <v-select v-model="draft.service.serviceType" label="服务类型" :items="serviceTypes" @update:model-value="applyServiceDefaults" />
          <p class="field-hint">{{ currentServiceAuthHint }}</p>
          <div class="link-fields-with-action">
            <v-text-field v-model="draft.service.internalUrl" label="内网 URL" />
            <v-text-field v-model="draft.service.externalUrl" label="公网 URL" />
            <v-btn prepend-icon="mdi-auto-fix" variant="outlined" :loading="loadingMetadata" @click="autoFillCardFromLink">从链接自动补全</v-btn>
          </div>
          <v-text-field v-if="draft.service.serviceType === 'generic'" v-model="draft.service.statusUrl" label="状态检测 URL" />
          <v-text-field
            v-else
            v-model="draft.service.token"
            label="API Key"
            :type="showServiceToken ? 'text' : 'password'"
            autocomplete="new-password"
            :append-inner-icon="showServiceToken ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
            @click:append-inner="showServiceToken = !showServiceToken"
          />
        </template>
        <template v-else-if="draft.type === 'docker'">
          <v-combobox
            v-model="draft.docker.containerId"
            label="容器 ID 或名称"
            :items="dockerContainers"
            item-title="label"
            item-value="id"
            :return-object="false"
            hint="可从当前 Docker 容器中选择，也可手动填写"
            persistent-hint
            @update:model-value="changeDockerContainer"
          />
          <div class="link-fields-with-action">
            <v-text-field v-model="draft.docker.internalUrl" label="内网 URL" />
            <v-text-field v-model="draft.docker.externalUrl" label="公网 URL" />
            <v-btn prepend-icon="mdi-auto-fix" variant="outlined" :loading="loadingMetadata" @click="autoFillCardFromLink">从链接自动补全</v-btn>
          </div>
        </template>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="close">取消</v-btn>
        <v-btn color="secondary" @click="submit">确定</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.dialog-form { display: grid; gap: 4px; padding-top: 20px !important; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 4px 16px; }
.card-option-fields { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap: 4px 16px; }
.icon-section { display: grid; gap: 14px; padding: 16px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgba(var(--v-theme-on-surface),.025); }
.icon-section-head { display: flex; align-items: baseline; gap: 10px; }
.icon-section-head strong { font-size: .9rem; letter-spacing: 0; }
.icon-section-head span { color: rgba(var(--v-theme-on-surface),.56); font-size: .75rem; }
.icon-source-tabs { border-bottom: 1px solid rgba(var(--v-theme-on-surface),.1); }
.icon-source-tabs :deep(.v-tab) { min-width: 0; padding-inline: 12px; color: rgba(var(--v-theme-on-surface),.68); font-size: .82rem; letter-spacing: 0; text-transform: none; }
.icon-source-tabs :deep(.v-tab--selected) { color: rgb(var(--v-theme-primary)); }
.icon-source-tabs :deep(.v-tab__slider) { height: 2px; }
.icon-editor-row { display: grid; grid-template-columns: 56px minmax(0,1fr); align-items: start; gap: 14px; }
.icon-preview { display: grid; width: 56px; height: 56px; place-items: center; overflow: hidden; border: 1px solid rgba(var(--v-theme-on-surface),.12); border-radius: 8px; background: rgb(var(--v-theme-surface)); color: rgb(var(--v-theme-on-surface)); }
.icon-preview img { width: 42px; height: 42px; object-fit: contain; }
.field-hint { margin: -8px 0 12px; color: rgba(var(--v-theme-on-surface), .62); font-size: .78rem; line-height: 1.5; }
.link-fields-with-action { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)) auto; align-items: start; gap: 16px; }
@media (max-width: 820px) {
  .form-grid, .card-option-fields, .link-fields-with-action { grid-template-columns: 1fr; }
}
@media (max-width: 520px) {
  .icon-section { padding-inline: 12px; }
  .icon-source-tabs :deep(.v-tab) { padding-inline: 4px; font-size: .78rem; }
  .icon-source-tabs :deep(.v-btn__prepend) { margin-inline-end: 5px; }
  .icon-editor-row { grid-template-columns: 44px minmax(0,1fr); gap: 10px; }
  .icon-preview { width: 44px; height: 44px; }
  .icon-preview img { width: 34px; height: 34px; }
}
</style>
