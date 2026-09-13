<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken } from '../lib/api'
import { appUrl } from '../lib/paths'
import { appState, loadAuth } from '../stores/app'

const router = useRouter()
const REMEMBERED_LOGIN_KEY = 'open-panel-remembered-login'
const rememberedLogin = loadRememberedLogin()
const form = ref({ username: rememberedLogin.username || '', password: rememberedLogin.password || '' })
const rememberPassword = ref(Boolean(rememberedLogin.password))
const loading = ref(false)
const error = ref('')
const showPassword = ref(false)
const initialized = computed(() => appState.auth.initialized)

function loadRememberedLogin() {
  try {
    const value = JSON.parse(localStorage.getItem(REMEMBERED_LOGIN_KEY) || '{}')
    return typeof value === 'object' && value ? value : {}
  } catch {
    localStorage.removeItem(REMEMBERED_LOGIN_KEY)
    return {}
  }
}

function updateRememberPassword(value) {
  rememberPassword.value = value
  if (!value) localStorage.removeItem(REMEMBERED_LOGIN_KEY)
}

function saveRememberedLogin() {
  if (!rememberPassword.value) {
    localStorage.removeItem(REMEMBERED_LOGIN_KEY)
    return
  }
  localStorage.setItem(REMEMBERED_LOGIN_KEY, JSON.stringify({
    username: form.value.username,
    password: form.value.password
  }))
}

onMounted(async () => {
  try {
    await loadAuth()
    if (appState.auth.authenticated) router.replace('/settings')
  } catch (e) { error.value = e.message }
})

async function submit() {
  error.value = ''
  loading.value = true
  try {
    const data = await api(initialized.value ? '/api/auth/login' : '/api/auth/setup', {
      method: 'POST', body: JSON.stringify(form.value)
    })
    setToken(data.token)
    saveRememberedLogin()
    appState.auth.authenticated = true
    router.replace('/settings')
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}
</script>

<template>
  <main class="auth-page">
    <router-link class="back-link" to="/" aria-label="返回导航页"><v-icon icon="mdi-arrow-left" /></router-link>
    <section class="auth-panel">
      <img :src="appUrl('icons/icon.svg')" alt="" />
      <div>
        <h1>{{ initialized ? '管理员登录' : '初始化 Open Panel' }}</h1>
        <p>{{ initialized ? '登录后管理导航、外观和安全策略' : '创建第一个管理员账号以继续' }}</p>
      </div>
      <v-alert v-if="error" type="error" variant="tonal" density="compact">{{ error }}</v-alert>
      <form autocomplete="on" @submit.prevent="submit">
        <v-text-field v-model="form.username" label="用户名" name="username" autocomplete="username" prepend-inner-icon="mdi-account-outline" required />
        <v-text-field v-model="form.password" label="密码" name="password" :type="showPassword ? 'text' : 'password'" :autocomplete="initialized ? 'current-password' : 'new-password'" prepend-inner-icon="mdi-lock-outline" :append-inner-icon="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" required @click:append-inner="showPassword = !showPassword" />
        <v-checkbox
          :model-value="rememberPassword"
          label="记住密码"
          color="primary"
          density="compact"
          hide-details
          @update:model-value="updateRememberPassword"
        />
        <v-btn block color="secondary" size="large" type="submit" :loading="loading">
          {{ initialized ? '登录' : '创建管理员' }}
        </v-btn>
      </form>
    </section>
  </main>
</template>

<style scoped>
.auth-page { min-height: 100svh; display: grid; place-items: center; padding: 24px; background: radial-gradient(circle at 20% 15%, rgba(233,255,112,.24), transparent 28%), rgb(var(--v-theme-background)); }
.back-link { position: fixed; top: 20px; left: 20px; display: grid; width: 42px; height: 42px; place-items: center; border: 1px solid rgba(var(--v-theme-on-surface),.12); border-radius: 8px; color: inherit; }
.auth-panel { display: grid; width: min(420px, 100%); gap: 22px; padding: 34px; border: 1px solid rgba(var(--v-theme-on-surface),.1); border-radius: 8px; background: rgb(var(--v-theme-surface)); box-shadow: 0 24px 70px rgba(22,28,25,.12); }
.auth-panel > img { width: 52px; height: 52px; border-radius: 8px; }
h1 { margin: 0; font-size: 1.55rem; letter-spacing: 0; }
p { margin: 6px 0 0; color: rgba(var(--v-theme-on-surface),.58); }
form { display: grid; gap: 4px; }
@media (max-width: 480px) { .auth-panel { padding: 26px 20px; } }
</style>
