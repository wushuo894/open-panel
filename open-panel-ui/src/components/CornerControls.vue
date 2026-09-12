<script setup>
import { computed, mergeProps, ref } from 'vue'
import { useRouter } from 'vue-router'
import { appState, changeNetwork } from '../stores/app'

const props = defineProps({ hoverOnly: Boolean, onCover: Boolean })
const router = useRouter()
const networkMenuOpen = ref(false)
const items = [
  { value: 'auto', title: '自动', icon: 'mdi-access-point-network' },
  { value: 'internal', title: '内网', icon: 'mdi-home-outline' },
  { value: 'external', title: '公网', icon: 'mdi-earth' }
]
const classes = computed(() => ({
  'hover-only': props.hoverOnly,
  'menu-open': networkMenuOpen.value,
  'on-cover': props.onCover
}))
const networkItem = computed(() => items.find(item => item.value === appState.network) || items[0])
</script>

<template>
  <div class="corner-hotspot" :class="classes">
    <div class="corner-controls">
      <v-menu v-model="networkMenuOpen" location="bottom end">
        <template #activator="{ props: menuProps }">
          <v-tooltip :text="`网络环境：${networkItem.title}`" location="bottom">
            <template #activator="{ props: tooltipProps }">
              <v-btn
                v-bind="mergeProps(menuProps, tooltipProps)"
                :icon="networkItem.icon"
                variant="text"
                size="small"
                :aria-label="`网络环境：${networkItem.title}`"
              />
            </template>
          </v-tooltip>
        </template>
        <v-list density="compact" nav>
          <v-list-item
            v-for="item in items"
            :key="item.value"
            :active="appState.network === item.value"
            :prepend-icon="item.icon"
            :title="item.title"
            @click="changeNetwork(item.value)"
          />
        </v-list>
      </v-menu>
      <v-tooltip :text="appState.auth.authenticated ? '设置' : '登录'" location="bottom">
        <template #activator="{ props: tooltipProps }">
          <v-btn
            v-bind="tooltipProps"
            :icon="appState.auth.authenticated ? 'mdi-cog' : 'mdi-login'"
            variant="text"
            size="small"
            :aria-label="appState.auth.authenticated ? '设置' : '登录'"
            @click="router.push(appState.auth.authenticated ? '/settings' : '/login')"
          />
        </template>
      </v-tooltip>
    </div>
  </div>
</template>

<style scoped>
.corner-hotspot {
  position: fixed;
  z-index: 30;
  top: 0;
  right: 0;
  padding-top: max(16px, env(safe-area-inset-top));
  padding-right: max(16px, env(safe-area-inset-right));
}
.corner-controls {
  display: flex;
  gap: 8px;
  padding: 6px;
  border: 1px solid rgba(255,255,255,.24);
  border-radius: 8px;
  background: rgba(20,24,25,.65);
  color: #fff;
  box-shadow: 0 8px 24px rgba(0,0,0,.18);
  backdrop-filter: blur(14px);
  transition: opacity .2s ease, transform .2s ease;
}
.corner-controls :deep(.v-btn) { color: currentColor; }
@media (hover: hover) and (pointer: fine) {
  .corner-hotspot.hover-only .corner-controls { opacity: 0; pointer-events: none; transform: translateY(-6px); }
  .corner-hotspot.hover-only:hover .corner-controls,
  .corner-hotspot.hover-only:focus-within .corner-controls,
  .corner-hotspot.hover-only.menu-open .corner-controls { opacity: 1; pointer-events: auto; transform: none; }
}
@media (max-width: 600px) { .corner-hotspot { padding-top: 10px; padding-right: 10px; } }
</style>
