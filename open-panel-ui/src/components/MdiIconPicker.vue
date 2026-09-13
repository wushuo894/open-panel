<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, default: 'MDI 图标' },
  hideDetails: Boolean
})
const emit = defineEmits(['update:modelValue'])

const icons = ref([])
const loading = ref(false)
let loaded = false

const items = computed(() => {
  if (!props.modelValue || icons.value.includes(props.modelValue)) return icons.value
  return [props.modelValue, ...icons.value]
})

async function loadIcons() {
  if (loaded || loading.value) return
  loading.value = true
  try {
    const stylesheet = await import('@mdi/font/css/materialdesignicons.css?raw')
    const names = new Set()
    for (const match of stylesheet.default.matchAll(/\.mdi-([a-z0-9-]+)::before/g)) {
      names.add(`mdi-${match[1]}`)
    }
    icons.value = [...names].sort()
    loaded = true
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <v-combobox
    :model-value="modelValue"
    :items="items"
    :label="label"
    :loading="loading"
    :hide-details="hideDetails"
    clearable
    auto-select-first
    no-data-text="未找到图标"
    @focus="loadIcons"
    @update:menu="value => { if (value) loadIcons() }"
    @update:model-value="value => emit('update:modelValue', value || '')"
  >
    <template #prepend-inner>
      <v-icon :icon="modelValue || 'mdi-shape-outline'" />
    </template>
    <template #item="{ props: itemProps, item }">
      <v-list-item v-bind="itemProps" :prepend-icon="item.raw" />
    </template>
  </v-combobox>
</template>
