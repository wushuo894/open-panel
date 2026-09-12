<script setup>
import { computed, ref } from 'vue'

const props = defineProps({ engines: { type: Array, default: () => [] }, glass: Boolean })
const query = ref('')
const selected = ref('')
const engine = computed(() => props.engines.find(item => item.id === selected.value) || props.engines[0])

function search() {
  const value = query.value.trim()
  if (!value || !engine.value) return
  window.open(engine.value.urlTemplate.replace('{query}', encodeURIComponent(value)), '_blank', 'noopener,noreferrer')
}
</script>

<template>
  <form class="search-bar" :class="{ glass }" role="search" @submit.prevent="search">
    <v-menu v-if="engine">
      <template #activator="{ props: menuProps }">
        <v-btn v-bind="menuProps" :icon="engine.icon" variant="text" aria-label="切换搜索引擎">
          <v-icon :icon="engine.icon" />
          <v-tooltip activator="parent">{{ engine.name }}</v-tooltip>
        </v-btn>
      </template>
      <v-list density="compact">
        <v-list-item v-for="item in engines" :key="item.id" :prepend-icon="item.icon" :title="item.name" @click="selected = item.id" />
      </v-list>
    </v-menu>
    <input v-model="query" :placeholder="engine ? `使用 ${engine.name} 搜索` : '搜索'" autocomplete="off" />
    <v-btn icon="mdi-magnify" variant="text" type="submit" aria-label="搜索">
      <v-icon icon="mdi-magnify" />
    </v-btn>
  </form>
</template>

<style scoped>
.search-bar {
  display: flex;
  align-items: center;
  width: min(660px, 100%);
  height: 56px;
  margin-inline: auto;
  padding-inline: 6px;
  border: 1px solid rgba(var(--v-theme-on-surface), .12);
  border-radius: 8px;
  background: rgb(var(--v-theme-surface));
  color: rgb(var(--v-theme-on-surface));
  box-shadow: 0 10px 35px rgba(22,29,25,.10);
}
.search-bar.glass { border-color: rgba(255,255,255,.28); background: rgba(255,255,255,.88); color: #151a18; backdrop-filter: blur(18px); }
input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: inherit; font: inherit; letter-spacing: 0; }
</style>
