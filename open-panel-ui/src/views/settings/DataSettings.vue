<script setup>
import { ref } from 'vue'
import { getToken, api } from '../../lib/api'
import { appUrl } from '../../lib/paths'

const emit = defineEmits(['imported', 'message', 'error'])
const importInput = ref(null)

// 下载包含配置、凭据和上传资源的 ZIP 备份。
async function exportConfig() {
  try {
    const response = await fetch(appUrl('/api/admin/config/export'), {
      headers: { Authorization: `Bearer ${getToken()}` }
    })
    if (!response.ok) throw new Error('导出失败')
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'open-panel-config.zip'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 校验并上传 ZIP 配置备份，导入结果由后端直接持久化。
async function importConfig(event) {
  const file = event.target.files?.[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.zip')) {
    emit('error', '请选择 ZIP 配置备份')
    event.target.value = ''
    return
  }
  const body = new FormData()
  body.append('file', file)
  try {
    const importedConfig = await api('/api/admin/config/import', { method: 'POST', body })
    emit('imported', importedConfig)
    emit('message', '配置已导入')
  } catch (requestError) {
    emit('error', requestError.message)
  }
  event.target.value = ''
}
</script>

<template>
  <section class="settings-section">
    <div class="section-heading"><div><h2>配置备份</h2><p>ZIP 备份包含完整配置、凭据和已上传图片，请妥善保管</p></div></div>
    <div class="action-row">
      <v-btn prepend-icon="mdi-download-outline" variant="outlined" @click="exportConfig">导出配置</v-btn>
      <v-btn prepend-icon="mdi-upload-outline" variant="outlined" @click="importInput.click()">导入配置</v-btn>
      <input ref="importInput" type="file" accept="application/zip,application/x-zip-compressed,.zip" hidden @change="importConfig" />
    </div>
  </section>
</template>

<style scoped>
.settings-section { padding-block: 28px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.section-heading h2 { margin: 0; font-size: 1.08rem; letter-spacing: 0; }
.section-heading p { margin: 5px 0 0; color: rgba(var(--v-theme-on-surface),.56); font-size: .84rem; }
.action-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
@media (max-width: 540px) {
  .section-heading { align-items: flex-start; flex-direction: column; }
}
</style>
