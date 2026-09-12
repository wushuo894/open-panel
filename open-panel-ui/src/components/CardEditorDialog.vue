<script setup>
import { computed, ref, watch } from 'vue'
import { api } from '../lib/api'
import { appUrl } from '../lib/paths'

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

const cardTypes = [
  { title: '自定义链接', value: 'custom' },
  { title: '系统信息', value: 'system' },
  { title: '软件服务', value: 'service' },
  { title: 'Docker 容器', value: 'docker' }
]
const serviceTypes = [
  { title: 'Emby', value: 'emby' },
  { title: 'ani-rss', value: 'ani-rss' },
  { title: 'qBittorrent', value: 'qbit' },
  { title: '通用 Web 服务', value: 'generic' }
]
const serviceDefaults = {
  emby: ['Emby', 'mdi-play-circle-outline', '媒体服务器'],
  'ani-rss': ['ani-rss', 'mdi-rss', '自动追番与下载'],
  qbit: ['qBittorrent', 'mdi-download-network-outline', '下载服务'],
  generic: ['Web 服务', 'mdi-application-outline', '自托管 Web 服务']
}
const serviceAuthHints = {
  emby: '填写 Emby API Key 后可显示版本、活跃播放和转码数量。',
  'ani-rss': '填写 ani-rss 设置页生成的 API Key。',
  qbit: '填写 qBittorrent 生成的 qbt_ 开头 API Key。',
  generic: '通用服务使用状态检测 URL 探测 HTTP 状态。'
}
const currentServiceAuthHint = computed(() => serviceAuthHints[draft.value?.service?.serviceType] || serviceAuthHints.generic)

watch(() => props.card, value => {
  if (!value) {
    draft.value = null
    return
  }
  draft.value = normalizeCard(JSON.parse(JSON.stringify(value)))
  error.value = ''
}, { immediate: true })

watch(() => props.modelValue, value => {
  if (value) loadDockerContainers()
})

function normalizeCard(card) {
  card.custom ||= { internalUrl: '', externalUrl: '' }
  card.system ||= { metric: 'overview' }
  card.service ||= { serviceType: 'generic', internalUrl: '', externalUrl: '', statusUrl: '', token: '' }
  card.docker ||= { containerId: '', internalUrl: '', externalUrl: '' }
  return card
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
    Object.assign(draft.value, { title: '系统状态', icon: 'mdi-server-outline', remark: 'CPU、内存、存储与网络' })
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

async function autoFillCustomCard() {
  const url = draft.value.custom.internalUrl?.trim() || draft.value.custom.externalUrl?.trim()
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
    if (metadata.iconUrl) draft.value.iconUrl = metadata.iconUrl
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

async function uploadIcon(value) {
  const file = Array.isArray(value) ? value[0] : value
  if (!file) return
  uploadingIcon.value = true
  error.value = ''
  const body = new FormData()
  body.append('file', file)
  try {
    const result = await api('/api/admin/assets/icon', { method: 'POST', body })
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
        <div class="form-grid">
          <v-text-field v-model="draft.title" label="标题" />
          <v-select v-model="draft.groupId" label="所属分组" :items="groups" item-title="title" item-value="id" />
          <v-select :model-value="draft.type" label="卡片类型" :items="cardTypes" @update:model-value="changeCardType" />
          <v-text-field v-model="draft.icon" label="MDI 图标" />
          <v-text-field v-model="draft.iconUrl" label="自定义图标 URL" />
          <v-select v-model="draft.openTarget" label="打开方式" :items="[{title:'新窗口',value:'new'},{title:'当前窗口',value:'self'}]" />
        </div>
        <div class="icon-upload-row">
          <span v-if="draft.iconUrl" class="icon-preview"><img :src="appUrl(draft.iconUrl)" alt="当前卡片图标" /></span>
          <v-file-input
            label="上传卡片图标"
            accept="image/png,image/jpeg,image/gif,image/webp"
            prepend-icon="mdi-image-plus-outline"
            :loading="uploadingIcon"
            hint="PNG、JPEG、GIF 或 WebP，最大 2 MB"
            persistent-hint
            @update:model-value="uploadIcon"
          />
        </div>
        <v-text-field v-model="draft.remark" label="备注" />
        <v-switch v-model="draft.enabled" label="在首页显示" color="primary" />

        <template v-if="draft.type === 'custom'">
          <div class="custom-link-fields">
            <v-text-field v-model="draft.custom.internalUrl" label="内网 URL" />
            <v-text-field v-model="draft.custom.externalUrl" label="公网 URL" />
            <v-btn prepend-icon="mdi-auto-fix" variant="outlined" :loading="loadingMetadata" @click="autoFillCustomCard">从链接自动补全</v-btn>
          </div>
        </template>
        <template v-else-if="draft.type === 'system'">
          <v-select v-model="draft.system.metric" label="系统信息" :items="[{title:'综合信息',value:'overview'},{title:'CPU',value:'cpu'},{title:'内存',value:'memory'},{title:'存储',value:'storage'},{title:'网络',value:'network'}]" />
        </template>
        <template v-else-if="draft.type === 'service'">
          <v-select v-model="draft.service.serviceType" label="服务类型" :items="serviceTypes" @update:model-value="applyServiceDefaults" />
          <p class="field-hint">{{ currentServiceAuthHint }}</p>
          <div class="form-grid">
            <v-text-field v-model="draft.service.internalUrl" label="内网 URL" />
            <v-text-field v-model="draft.service.externalUrl" label="公网 URL" />
          </div>
          <v-text-field v-if="draft.service.serviceType === 'generic'" v-model="draft.service.statusUrl" label="状态检测 URL" />
          <v-text-field v-else v-model="draft.service.token" label="API Key" type="password" autocomplete="new-password" />
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
          />
          <div class="form-grid">
            <v-text-field v-model="draft.docker.internalUrl" label="内网 URL" />
            <v-text-field v-model="draft.docker.externalUrl" label="公网 URL" />
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
.icon-upload-row { display: flex; align-items: flex-start; gap: 14px; }
.icon-upload-row > :last-child { flex: 1; }
.icon-preview { display: grid; width: 56px; height: 56px; flex: 0 0 56px; place-items: center; overflow: hidden; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; }
.icon-preview img { width: 42px; height: 42px; object-fit: contain; }
.field-hint { margin: -8px 0 12px; color: rgba(var(--v-theme-on-surface), .62); font-size: .78rem; line-height: 1.5; }
.custom-link-fields { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)) auto; align-items: start; gap: 16px; }
@media (max-width: 820px) {
  .form-grid, .custom-link-fields { grid-template-columns: 1fr; }
}
</style>
