<script setup>
import { computed, ref } from 'vue'
import { api } from '../../lib/api'
import { appUrl } from '../../lib/paths'
import { extractThemeColor, normalizeThemeColor } from '../../lib/theme'

const props = defineProps({
  config: { type: Object, required: true }
})
const emit = defineEmits(['message', 'error'])

const uploadingBackground = ref(false)
const extractingThemeColor = ref(false)
const predefinedThemeColors = [
  '#d8f257', '#409eff', '#109d58', '#bf3545', '#cb7574', '#9aaec7', '#2ec5b6', '#1c1c1c', '#f7b1a9',
  '#b18874', '#e9ba86', '#f68f6c', '#f0458b', '#c35653', '#40494e', '#6f0000', '#8d3647',
  '#e6c5d0', '#2377b3', '#49312d', '#7c9ab6', '#a5b18d', '#e8662a', '#ab5d50'
]

const sortedGroups = computed(() => [...(props.config.groups || [])]
  .sort((left, right) => (left.sort ?? 0) - (right.sort ?? 0)))
const coverGroupId = computed({
  get: () => props.config.page.cover.groupIds?.[0] || null,
  set: value => { props.config.page.cover.groupIds = value ? [value] : [] }
})
const wallpaperText = computed({
  get: () => props.config.page.cover.wallpapers.join('\n') || '',
  set: value => { props.config.page.cover.wallpapers = value.split('\n').map(item => item.trim()).filter(Boolean) }
})
const previewWallpaper = computed(() => appUrl(props.config.page.cover.wallpapers?.[0] || props.config.site.background || ''))
const footerText = computed({
  get: () => props.config.page.footer.lines.join('\n') || '',
  set: value => { props.config.page.footer.lines = value.split('\n').slice(0, 4) }
})

// 上传背景图并将新资源放到壁纸列表首位。
async function uploadBackground(value) {
  const file = Array.isArray(value) ? value[0] : value
  if (!file) return
  uploadingBackground.value = true
  const body = new FormData()
  body.append('file', file)
  try {
    const result = await api('/api/admin/assets/background', { method: 'POST', body })
    props.config.page.cover.wallpapers = [result.url, ...props.config.page.cover.wallpapers.filter(url => url !== result.url)]
    props.config.site.background = result.url
    emit('message', '背景图片已上传并设为当前壁纸，点击保存后生效')
  } catch (requestError) {
    emit('error', requestError.message)
  } finally {
    uploadingBackground.value = false
  }
}

// 失焦时将手动输入的主题色规范为合法十六进制颜色。
function normalizeThemeColorField() {
  props.config.site.themeColor = normalizeThemeColor(props.config.site.themeColor)
}

// 从当前壁纸提取代表色并写入配置草稿。
async function pickThemeColorFromWallpaper() {
  if (!previewWallpaper.value) {
    emit('error', '请先设置壁纸')
    return
  }
  extractingThemeColor.value = true
  try {
    props.config.site.themeColor = await extractThemeColor(previewWallpaper.value)
    emit('message', `已从壁纸选取主题色 ${props.config.site.themeColor}，点击保存后生效`)
  } catch (requestError) {
    emit('error', requestError.message)
  } finally {
    extractingThemeColor.value = false
  }
}
</script>

<template>
  <section class="settings-section">
    <div class="section-heading"><div><h2>站点与页面</h2><p>名称、主题与首页呈现方式</p></div></div>
    <div class="form-grid">
      <v-text-field v-model="config.site.title" label="网站标题" />
      <v-text-field v-model="config.site.icon" label="网站图标 URL" />
      <v-select v-model="config.site.theme" label="主题" :items="[{title:'跟随系统',value:'system'},{title:'浅色',value:'light'},{title:'深色',value:'dark'}]" />
      <v-select v-model="config.page.mode" label="页面模式" :items="[{title:'大封面模式',value:'cover'},{title:'列表模式',value:'list'}]" />
      <v-select v-model="config.page.groupLayout" label="卡片组布局" :items="[{title:'纵向分组',value:'sections'},{title:'Tabs 切换',value:'tabs'}]" />
      <div class="theme-color-control">
        <input v-model="config.site.themeColor" class="theme-color-swatch" type="color" aria-label="选择主题色" title="选择主题色" />
        <v-text-field v-model="config.site.themeColor" label="主题色" hide-details @blur="normalizeThemeColorField" />
        <v-btn variant="outlined" prepend-icon="mdi-eyedropper-variant" :loading="extractingThemeColor" @click="pickThemeColorFromWallpaper">从壁纸选取</v-btn>
      </div>
      <div class="theme-color-presets" aria-label="推荐主题色">
        <span>推荐颜色</span>
        <button
          v-for="color in predefinedThemeColors"
          :key="color"
          type="button"
          class="theme-color-preset"
          :class="{ selected: normalizeThemeColor(config.site.themeColor) === color }"
          :style="{ '--preset-color': color }"
          :aria-label="`使用主题色 ${color}`"
          :title="color"
          @click="config.site.themeColor = color"
        >
          <v-icon v-if="normalizeThemeColor(config.site.themeColor) === color" icon="mdi-check" size="15" />
        </button>
      </div>
      <div class="site-page-switches">
        <v-switch v-if="config.page.groupLayout === 'tabs'" v-model="config.page.tabsShowAll" label="显示“全部”标签" color="primary" hide-details />
        <v-switch v-model="config.site.cornerControlsHoverOnly" label="右上角按钮仅悬停时显示" color="primary" hide-details />
      </div>
    </div>
  </section>

  <section class="settings-section">
    <div class="section-heading"><div><h2>卡片与搜索框</h2><p>统一调整首页导航控件的外观</p></div><v-switch v-model="config.page.searchVisible" label="显示搜索框" color="primary" hide-details /></div>
    <div class="form-grid">
      <v-slider v-model="config.page.surfaceTransparency" label="透明度" :min="0" :max="0.9" :step="0.05" thumb-label />
      <v-slider v-model="config.page.surfaceRadius" label="圆角（px）" :min="0" :max="32" :step="1" thumb-label />
    </div>
  </section>

  <section class="settings-section">
    <div class="section-heading"><div><h2>Banner</h2><p>首页时间、标题和一句话</p></div><v-switch v-model="config.page.banner.visible" label="显示" color="primary" hide-details /></div>
    <div class="form-grid">
      <v-switch v-model="config.page.banner.showTime" label="显示时间" color="primary" hide-details />
      <v-switch v-model="config.page.banner.showSeconds" label="精确到秒" color="primary" hide-details />
      <v-switch v-model="config.page.banner.showDate" label="显示日期" color="primary" hide-details />
      <v-switch v-model="config.page.banner.showWeekday" label="显示周几" color="primary" hide-details />
      <v-switch v-model="config.page.banner.showTitle" label="显示自定义标题" color="primary" hide-details />
      <v-switch v-model="config.page.banner.showQuote" label="显示自定义一句话" color="primary" hide-details />
      <v-text-field v-model="config.page.banner.title" label="Banner 标题" />
      <v-text-field v-model="config.page.banner.quote" label="自定义一句话" />
    </div>
  </section>

  <section class="settings-section">
    <div class="section-heading"><div><h2>壁纸</h2><p>上传图片或每行填写一个 URL，多张时自动轮换</p></div></div>
    <div class="background-upload">
      <span
        class="background-preview"
        :style="{
          backgroundImage: `linear-gradient(rgba(8, 12, 11, ${config.site.backgroundOverlay}), rgba(8, 12, 11, ${config.site.backgroundOverlay})), url(${previewWallpaper})`
        }"
      />
      <v-file-input
        label="上传背景图片"
        accept=".avif,.png,.webp,.jpg,.jpeg,.gif"
        prepend-icon="mdi-image-plus-outline"
        :loading="uploadingBackground"
        hint="AVIF、PNG、WebP、JPG、JPEG 或 GIF，最大 20 MB"
        persistent-hint
        @update:model-value="uploadBackground"
      />
    </div>
    <v-textarea v-model="wallpaperText" label="壁纸列表" rows="4" />
    <div class="form-grid">
      <v-slider v-model="config.site.backgroundOverlay" label="壁纸遮罩" :min="0" :max="0.9" :step="0.05" thumb-label />
      <v-number-input v-model="config.page.cover.intervalSeconds" label="轮换间隔（秒）" :min="5" :max="3600" />
      <v-select v-model="coverGroupId" label="封面中显示的分组" :items="sortedGroups" item-title="title" item-value="id" clearable />
    </div>
  </section>

  <section class="settings-section">
    <div class="section-heading"><div><h2>页脚</h2><p>最多显示四行文字</p></div><v-switch v-model="config.page.footer.visible" label="显示" color="primary" hide-details /></div>
    <v-textarea v-model="footerText" label="页脚文字" rows="4" counter="4" />
  </section>
</template>

<style scoped>
.settings-section { padding-block: 28px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.section-heading h2 { margin: 0; font-size: 1.08rem; letter-spacing: 0; }
.section-heading p { margin: 5px 0 0; color: rgba(var(--v-theme-on-surface),.56); font-size: .84rem; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 4px 16px; }
.theme-color-control { display: grid; grid-column: 1 / -1; grid-template-columns: 48px minmax(180px, 280px) auto; align-items: center; justify-content: start; gap: 10px; margin-block: 2px 8px; }
.theme-color-swatch { width: 48px; height: 48px; padding: 3px; border: 1px solid rgba(var(--v-theme-on-surface),.16); border-radius: 8px; background: rgb(var(--v-theme-surface)); cursor: pointer; }
.theme-color-swatch::-webkit-color-swatch-wrapper { padding: 0; }
.theme-color-swatch::-webkit-color-swatch { border: 0; border-radius: 5px; }
.theme-color-swatch::-moz-color-swatch { border: 0; border-radius: 5px; }
.theme-color-presets { display: flex; grid-column: 1 / -1; align-items: center; flex-wrap: wrap; gap: 8px; margin: -2px 0 10px; }
.theme-color-presets > span { margin-right: 3px; color: rgba(var(--v-theme-on-surface),.58); font-size: .78rem; }
.theme-color-preset { display: grid; width: 28px; height: 28px; padding: 0; border: 2px solid rgba(var(--v-theme-on-surface),.12); border-radius: 50%; place-items: center; background: var(--preset-color); color: white; cursor: pointer; box-shadow: 0 0 0 1px rgba(var(--v-theme-surface),.9); transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease; }
.theme-color-preset:hover { transform: translateY(-1px); box-shadow: 0 0 0 2px rgba(var(--v-theme-primary),.32); }
.theme-color-preset.selected { border-color: rgb(var(--v-theme-surface)); box-shadow: 0 0 0 2px rgb(var(--v-theme-primary)); }
.theme-color-preset:focus-visible { outline: 2px solid rgb(var(--v-theme-primary)); outline-offset: 2px; }
.site-page-switches { display: grid; grid-column: 1 / -1; grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: center; gap: 8px 24px; }
.background-upload { display: grid; grid-template-columns: minmax(180px, 320px) 1fr; align-items: center; gap: 18px; margin-bottom: 18px; }
.background-preview { width: 100%; aspect-ratio: 16 / 9; border-radius: 8px; background-color: rgba(var(--v-theme-on-surface),.08); background-position: center; background-size: cover; }
@media (max-width: 820px) {
  .form-grid { grid-template-columns: 1fr; }
  .background-upload { grid-template-columns: 1fr; }
  .site-page-switches { grid-template-columns: 1fr; }
}
@media (max-width: 540px) {
  .section-heading { align-items: flex-start; flex-direction: column; }
  .theme-color-control { grid-template-columns: 44px minmax(0,1fr); }
  .theme-color-swatch { width: 44px; height: 44px; }
  .theme-color-control .v-btn { grid-column: 1 / -1; }
}
</style>
