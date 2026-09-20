<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import { api, getToken, setToken } from '../lib/api'
import { appUrl } from '../lib/paths'
import { applySiteTheme } from '../lib/theme'
import { appState } from '../stores/app'
import AboutSettings from './settings/AboutSettings.vue'
import AppearanceSettings from './settings/AppearanceSettings.vue'
import ContentSettings from './settings/ContentSettings.vue'
import DataSettings from './settings/DataSettings.vue'
import DockerSettings from './settings/DockerSettings.vue'
import SecuritySettings from './settings/SecuritySettings.vue'

const router = useRouter()
const theme = useTheme()
const tab = ref('appearance')
const config = ref(null)
const loading = ref(true)
const saving = ref(false)
const message = ref('')
const error = ref('')
const dockerAvailable = ref(false)

onMounted(load)

watch(() => [config.value?.site?.theme, config.value?.site?.themeColor], () => {
  if (config.value?.site) applySiteTheme(theme, config.value.site)
})

// 加载所有设置页共享的配置草稿。
async function load() {
  loading.value = true
  try {
    config.value = await api('/api/admin/config')
  } catch (requestError) {
    if (!getToken()) router.replace('/login')
    else error.value = requestError.message
  } finally {
    loading.value = false
  }
}

// 保存前统一整理分组和卡片顺序，随后一次性提交完整配置。
async function save() {
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    normalizeAllSort()
    config.value = await api('/api/admin/config', { method: 'PUT', body: JSON.stringify(config.value) })
    message.value = '设置已保存'
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    saving.value = false
  }
}

// 将当前数组顺序写回 sort 字段，保证首页与设置页显示一致。
function normalizeAllSort() {
  const groups = [...(config.value?.groups || [])].sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
  groups.forEach((group, groupIndex) => {
    group.sort = groupIndex
    const cards = (config.value?.cards || [])
      .filter(card => card.groupId === group.id)
      .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0))
    cards.forEach((card, cardIndex) => { card.sort = cardIndex })
  })
}

// 接收子页面的操作结果，并清理旧错误提示。
function showMessage(value) {
  message.value = value
  error.value = ''
}

// 接收子页面的异常信息，并清理旧成功提示。
function showError(value) {
  error.value = value
  message.value = ''
}

// 清理本地登录状态并返回导航首页。
function logout() {
  api('/api/auth/logout', { method: 'POST' }).catch(() => {})
  setToken('')
  appState.auth.authenticated = false
  router.replace('/')
}
</script>

<template>
  <main class="settings-page">
    <header class="settings-header">
      <div class="header-inner content-width">
        <v-btn icon="mdi-arrow-left" variant="text" aria-label="返回导航页" @click="router.push('/')"><v-icon icon="mdi-arrow-left" /></v-btn>
        <img :src="appUrl('icons/icon.svg')" alt="" />
        <div><strong>Open Panel</strong><span>设置</span></div>
        <v-spacer />
        <v-btn icon="mdi-logout" variant="text" aria-label="退出登录" @click="logout"><v-icon icon="mdi-logout" /><v-tooltip activator="parent">退出登录</v-tooltip></v-btn>
        <v-btn color="secondary" prepend-icon="mdi-content-save-outline" :loading="saving" @click="save">保存</v-btn>
      </div>
    </header>

    <v-progress-linear v-if="loading" indeterminate color="secondary" />
    <div v-if="config" class="settings-layout content-width">
      <v-tabs v-model="tab" color="primary" class="settings-tabs" show-arrows>
        <v-tab value="appearance" prepend-icon="mdi-palette-outline">外观</v-tab>
        <v-tab value="content" prepend-icon="mdi-view-grid-outline">内容</v-tab>
        <v-tab v-if="dockerAvailable" value="docker" prepend-icon="mdi-docker">Docker</v-tab>
        <v-tab value="security" prepend-icon="mdi-shield-lock-outline">安全</v-tab>
        <v-tab value="data" prepend-icon="mdi-database-outline">数据</v-tab>
        <v-tab value="about" prepend-icon="mdi-information-outline">关于</v-tab>
      </v-tabs>

      <v-alert v-if="message" type="success" variant="tonal" closable @click:close="message = ''">{{ message }}</v-alert>
      <v-alert v-if="error" type="error" variant="tonal" closable @click:close="error = ''">{{ error }}</v-alert>

      <v-window v-model="tab" class="settings-window">
        <v-window-item value="appearance">
          <AppearanceSettings :config="config" @message="showMessage" @error="showError" />
        </v-window-item>
        <v-window-item value="content">
          <ContentSettings :config="config" @message="showMessage" @error="showError" />
        </v-window-item>
        <v-window-item value="docker" eager>
          <DockerSettings
            @availability-change="dockerAvailable = $event"
            @message="showMessage"
            @error="showError"
          />
        </v-window-item>
        <v-window-item value="security">
          <SecuritySettings :config="config" @error="showError" />
        </v-window-item>
        <v-window-item value="data">
          <DataSettings @imported="config = $event" @message="showMessage" @error="showError" />
        </v-window-item>
        <v-window-item value="about">
          <AboutSettings
            :config="config"
            :active="tab === 'about'"
            @message="showMessage"
            @error="showError"
          />
        </v-window-item>
      </v-window>
    </div>
  </main>
</template>

<style scoped>
.settings-page { min-height: 100svh; background: rgb(var(--v-theme-background)); }
.settings-header { position: sticky; z-index: 20; top: 0; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); background: rgba(var(--v-theme-surface),.92); backdrop-filter: blur(14px); }
.header-inner { display: flex; align-items: center; min-height: 66px; gap: 10px; }
.header-inner > img { width: 34px; height: 34px; border-radius: 8px; }
.header-inner > div { display: grid; line-height: 1.1; }
.header-inner span { margin-top: 4px; color: rgba(var(--v-theme-on-surface),.5); font-size: .74rem; }
.settings-layout { padding-block: 16px 64px; }
.settings-tabs { margin-bottom: 12px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.08); }
.settings-window { margin-inline: -24px; }
.settings-window :deep(.v-window-item) { padding-inline: 24px; }
@media (max-width: 540px) {
  .settings-window { margin-inline: -12px; }
  .settings-window :deep(.v-window-item) { padding-inline: 20px; }
  .header-inner > div { display: none; }
}
</style>
