<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import MarkdownIt from 'markdown-it'
import { api } from '../../lib/api'
import { appUrl } from '../../lib/paths'

const props = defineProps({
  config: { type: Object, required: true },
  active: { type: Boolean, default: false }
})
const emit = defineEmits(['message', 'error'])

const markdownRenderer = new MarkdownIt({ html: false, linkify: true, breaks: true })
const defaultLinkOpen = markdownRenderer.renderer.rules.link_open
  || ((tokens, index, options, environment, self) => self.renderToken(tokens, index, options))
markdownRenderer.renderer.rules.link_open = (tokens, index, options, environment, self) => {
  tokens[index].attrSet('target', '_blank')
  tokens[index].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, index, options, environment, self)
}

const updateInfo = ref(null)
const updateLoading = ref(false)
const updateAutoChecked = ref(false)
const updateConfirmDialog = ref(false)
const githubTokenExpanded = ref(false)
const visibleSecrets = ref({ githubToken: false })
const githubTokenConfigured = computed(() => Boolean(props.config.update.githubToken?.trim()))
const renderedUpdateNotes = computed(() => markdownRenderer.render(updateInfo.value?.notes?.trim() || '该版本未提供更新说明。'))

onMounted(loadCurrentVersion)

watch(() => props.active, active => {
  if (active && !updateAutoChecked.value) {
    updateAutoChecked.value = true
    checkUpdate()
  }
}, { immediate: true })

// 先读取本地版本，GitHub 检查失败时仍能展示当前版本号。
async function loadCurrentVersion() {
  try {
    updateInfo.value = await api('/api/admin/version')
  } catch (requestError) {
    emit('error', requestError.message)
  }
}

// 从后端检查 GitHub Release，并更新当前版本和最新版本信息。
async function checkUpdate() {
  updateLoading.value = true
  try {
    updateInfo.value = await api('/api/admin/update')
  } catch (requestError) {
    emit('error', requestError.message)
  } finally {
    updateLoading.value = false
  }
}

// 安装新版本并等待服务健康检查恢复后刷新页面。
async function installUpdate() {
  updateConfirmDialog.value = false
  updateLoading.value = true
  try {
    const result = await api('/api/admin/update/install', { method: 'POST' })
    if (!result.restarting) {
      emit('message', '更新任务已提交')
      return
    }
    emit('message', '更新已安装，正在重启服务')
    for (let attempt = 0; attempt < 24; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 5000))
      try {
        const response = await fetch(appUrl('/api/public/health'), { cache: 'no-store' })
        if (response.ok) {
          location.reload()
          return
        }
      } catch {
        // 服务重启期间健康检查暂时不可用，继续等待下一次轮询。
      }
    }
    throw new Error('服务重启超时，请稍后刷新页面')
  } catch (requestError) {
    emit('error', requestError.message)
  } finally {
    updateLoading.value = false
  }
}
</script>

<template>
  <section class="settings-section">
    <div class="section-heading"><div><h2>项目更新</h2><p>从 GitHub Releases 检查稳定版本</p></div></div>
    <div class="update-row">
      <div class="update-details">
        <strong>Open Panel <span v-if="updateInfo?.currentVersion" class="version-badge">v{{ updateInfo.currentVersion }}</span></strong>
        <small v-if="updateInfo?.latestVersion">当前 {{ updateInfo.currentVersion }} · 最新 {{ updateInfo.latestVersion }}</small>
        <small v-else>尚未检查更新</small>
        <v-btn
          prepend-icon="mdi-github"
          :append-icon="githubTokenExpanded ? 'mdi-chevron-up' : 'mdi-chevron-down'"
          variant="text"
          size="small"
          class="update-token-toggle"
          @click="githubTokenExpanded = !githubTokenExpanded"
        >GitHub Token · {{ githubTokenConfigured ? '已配置' : '未配置' }}</v-btn>
        <v-expand-transition>
          <v-text-field
            v-if="githubTokenExpanded"
            v-model="config.update.githubToken"
            label="GitHub Token"
            :type="visibleSecrets.githubToken ? 'text' : 'password'"
            autocomplete="off"
            prepend-inner-icon="mdi-key-outline"
            :append-inner-icon="visibleSecrets.githubToken ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
            hint="可选，仅用于 GitHub API 更新检查，避免匿名请求受 IP 频率限制"
            persistent-hint
            clearable
            density="compact"
            variant="outlined"
            class="update-token"
            @click:append-inner="visibleSecrets.githubToken = !visibleSecrets.githubToken"
          />
        </v-expand-transition>
      </div>
      <v-spacer />
      <v-btn v-if="!updateInfo?.available" variant="outlined" prepend-icon="mdi-refresh" :loading="updateLoading" @click="checkUpdate">检查更新</v-btn>
      <v-btn v-if="updateInfo?.available" color="secondary" prepend-icon="mdi-download" :loading="updateLoading" @click="updateConfirmDialog = true">安装更新</v-btn>
    </div>
  </section>
  <section class="settings-section">
    <div class="section-heading"><div><h2>开源项目</h2><p>GPL-2.0 · GitHub</p></div></div>
    <div class="action-row">
      <v-btn href="https://github.com/wushuo894/open-panel" target="_blank" prepend-icon="mdi-github" variant="outlined">wushuo894/open-panel</v-btn>
      <v-btn
        href="https://open-panel.wushuo.top"
        target="_blank"
        rel="noopener noreferrer"
        prepend-icon="mdi-book-open-variant"
        variant="outlined"
      >在线文档</v-btn>
      <v-btn
        href="https://ifdian.net/a/wushuo894"
        target="_blank"
        rel="noopener noreferrer"
        prepend-icon="mdi-lightning-bolt"
        variant="flat"
        class="afdian-button"
      >在爱发电支持我</v-btn>
    </div>
  </section>

  <v-dialog v-model="updateConfirmDialog" max-width="640" scrollable>
    <v-card>
      <v-card-title class="update-confirm-title">
        <v-icon icon="mdi-update" color="primary" />
        <span>更新至 v{{ updateInfo?.latestVersion }}</span>
      </v-card-title>
      <v-card-text class="update-confirm-content">
        <p>当前版本 v{{ updateInfo?.currentVersion }}，确认后将下载并安装新版本。</p>
        <div class="release-notes">
          <strong>更新内容</strong>
          <div class="release-notes-markdown" v-html="renderedUpdateNotes" />
        </div>
      </v-card-text>
      <v-card-actions>
        <v-btn
          v-if="updateInfo?.releaseUrl"
          :href="updateInfo.releaseUrl"
          target="_blank"
          rel="noopener noreferrer"
          prepend-icon="mdi-open-in-new"
          variant="text"
        >查看发布页</v-btn>
        <v-spacer />
        <v-btn @click="updateConfirmDialog = false">取消</v-btn>
        <v-btn color="secondary" prepend-icon="mdi-download" @click="installUpdate">确认更新</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.settings-section { padding-block: 28px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.section-heading h2 { margin: 0; font-size: 1.08rem; letter-spacing: 0; }
.section-heading p { margin: 5px 0 0; color: rgba(var(--v-theme-on-surface),.56); font-size: .84rem; }
.action-row, .update-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.afdian-button { background: #946ce6 !important; color: #fff !important; box-shadow: 0 4px 12px rgba(148, 108, 230, .28); }
.afdian-button:hover { background: #835bd6 !important; }
.afdian-button :deep(.v-icon) { color: #ffe36e; }
.update-row { align-items: flex-start; }
.update-row small { margin-top: 3px; color: rgba(var(--v-theme-on-surface),.56); }
.update-details { display: grid; flex: 1 1 420px; min-width: 0; }
.version-badge { margin-left: 6px; color: rgb(var(--v-theme-primary)); font-size: .82rem; font-weight: 650; }
.update-token-toggle { width: fit-content; margin-top: 8px; margin-left: -8px; color: rgba(var(--v-theme-on-surface), .68); }
.update-token { width: 100%; max-width: 360px; margin-top: 8px; }
.update-confirm-title { display: flex; align-items: center; gap: 10px; }
.update-confirm-content { display: grid; gap: 16px; }
.update-confirm-content > p { margin: 0; color: rgba(var(--v-theme-on-surface), .68); }
.release-notes { display: grid; gap: 8px; min-height: 120px; padding: 14px; border: 1px solid rgba(var(--v-theme-on-surface), .1); border-radius: 8px; background: rgba(var(--v-theme-on-surface), .035); }
.release-notes-markdown { max-height: 320px; overflow: auto; color: rgb(var(--v-theme-on-surface)); font-size: .88rem; line-height: 1.65; overflow-wrap: anywhere; }
.release-notes-markdown :deep(> :first-child) { margin-top: 0; }
.release-notes-markdown :deep(> :last-child) { margin-bottom: 0; }
.release-notes-markdown :deep(h1), .release-notes-markdown :deep(h2), .release-notes-markdown :deep(h3) { margin: 16px 0 7px; font-size: 1rem; letter-spacing: 0; }
.release-notes-markdown :deep(p), .release-notes-markdown :deep(ul), .release-notes-markdown :deep(ol), .release-notes-markdown :deep(blockquote) { margin: 7px 0; }
.release-notes-markdown :deep(ul), .release-notes-markdown :deep(ol) { padding-left: 22px; }
.release-notes-markdown :deep(a) { color: rgb(var(--v-theme-primary)); }
.release-notes-markdown :deep(code) { padding: 2px 5px; border-radius: 4px; background: rgba(var(--v-theme-on-surface), .08); font-size: .84em; }
.release-notes-markdown :deep(pre) { overflow: auto; padding: 10px; border-radius: 6px; background: rgba(var(--v-theme-on-surface), .08); }
.release-notes-markdown :deep(pre code) { padding: 0; background: transparent; }
.release-notes-markdown :deep(blockquote) { padding-left: 10px; border-left: 3px solid rgba(var(--v-theme-primary), .5); color: rgba(var(--v-theme-on-surface), .68); }
@media (max-width: 540px) {
  .section-heading { align-items: flex-start; flex-direction: column; }
}
</style>
