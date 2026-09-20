<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken } from '../../lib/api'
import { appState } from '../../stores/app'

const props = defineProps({
  config: { type: Object, required: true }
})
const emit = defineEmits(['error'])
const router = useRouter()

const usernameForm = ref({ newUsername: props.config.security.username, currentPassword: '' })
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const visibleSecrets = ref({ usernamePassword: false, currentPassword: false, newPassword: false, confirmPassword: false })
const changingUsername = ref(false)
const changingPassword = ref(false)

const corsText = computed({
  get: () => props.config.security.corsOrigins.join('\n') || '',
  set: value => { props.config.security.corsOrigins = lines(value) }
})
const allowlistText = computed({
  get: () => props.config.security.ipAllowlist.join('\n') || '',
  set: value => { props.config.security.ipAllowlist = lines(value) }
})
const proxiesText = computed({
  get: () => props.config.security.trustedProxyIps.join('\n') || '',
  set: value => { props.config.security.trustedProxyIps = lines(value) }
})

// 将多行网络规则整理为去空的数组。
function lines(value) {
  return value.split('\n').map(item => item.trim()).filter(Boolean)
}

// 修改管理员密码，成功后清理令牌并返回登录页。
async function changePassword() {
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    emit('error', '两次输入的新密码不一致')
    return
  }
  changingPassword.value = true
  try {
    await api('/api/admin/password', {
      method: 'POST',
      body: JSON.stringify({
        currentPassword: passwordForm.value.currentPassword,
        newPassword: passwordForm.value.newPassword
      })
    })
    clearLoginAndRedirect()
  } catch (requestError) {
    emit('error', requestError.message)
  } finally {
    changingPassword.value = false
  }
}

// 修改管理员用户名，并同步本地记住的登录账号。
async function changeUsername() {
  changingUsername.value = true
  const username = usernameForm.value.newUsername.trim()
  try {
    await api('/api/admin/username', {
      method: 'POST',
      body: JSON.stringify({ currentPassword: usernameForm.value.currentPassword, newUsername: username })
    })
    try {
      const key = 'open-panel-remembered-login'
      const remembered = JSON.parse(localStorage.getItem(key) || '{}')
      if (remembered?.password) localStorage.setItem(key, JSON.stringify({ ...remembered, username }))
    } catch {
      localStorage.removeItem('open-panel-remembered-login')
    }
    clearLoginAndRedirect()
  } catch (requestError) {
    emit('error', requestError.message)
  } finally {
    changingUsername.value = false
  }
}

// 统一清理认证状态，确保凭据变更后旧令牌不再使用。
function clearLoginAndRedirect() {
  setToken('')
  appState.auth.authenticated = false
  router.replace('/login')
}
</script>

<template>
  <section class="settings-section">
    <div class="section-heading"><div><h2>访问权限</h2><p>匿名用户只有导航页只读权限，不能进入设置</p></div></div>
    <div class="switch-grid">
      <v-switch v-model="config.security.anonymousAccess" label="允许免登录访问" color="primary" />
      <v-switch v-model="config.security.forbidMultipleLogin" label="禁止多端登录" color="primary" />
      <v-switch v-model="config.security.forbidPublicAccess" label="禁止公网访问" color="primary" />
      <v-switch v-model="config.security.invalidateOnIpChange" label="IP 改变后登录失效" color="primary" />
      <v-switch v-model="config.security.limitLoginAttempts" label="限制登录尝试次数" color="primary" />
      <v-switch v-model="config.security.allowCors" label="允许公开接口跨域" color="primary" />
    </div>
    <div class="form-grid">
      <v-number-input v-model="config.security.tokenValidHours" label="登录有效时间（小时）" hint="填写 0 表示永久有效" persistent-hint :min="0" :max="8760" />
      <v-number-input v-model="config.security.maxLoginAttempts" label="最大尝试次数" :min="1" :max="100" :disabled="!config.security.limitLoginAttempts" />
      <v-number-input v-model="config.security.loginLockMinutes" label="锁定时间（分钟）" hint="失败次数达到上限后，该 IP 暂停登录的时长" persistent-hint :min="1" :max="1440" :disabled="!config.security.limitLoginAttempts" />
    </div>
  </section>
  <section class="settings-section">
    <div class="section-heading"><div><h2>网络信任</h2><p>每行填写一个 IP 或 CIDR；白名单只限制登录和管理接口</p></div></div>
    <div class="form-grid">
      <v-textarea v-model="allowlistText" label="IP 白名单" rows="5" />
      <v-textarea v-model="proxiesText" label="信任的反代 IP" rows="5" />
      <v-textarea v-model="corsText" label="允许跨域的来源" rows="5" :disabled="!config.security.allowCors" placeholder="https://example.com" />
    </div>
  </section>
  <section class="settings-section">
    <div class="section-heading"><div><h2>管理员用户名</h2><p>修改后所有现有登录令牌立即失效</p></div></div>
    <form class="username-form" @submit.prevent="changeUsername">
      <v-text-field v-model="usernameForm.newUsername" label="新用户名" autocomplete="username" />
      <v-text-field
        v-model="usernameForm.currentPassword"
        label="当前密码"
        :type="visibleSecrets.usernamePassword ? 'text' : 'password'"
        autocomplete="current-password"
        :append-inner-icon="visibleSecrets.usernamePassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
        @click:append-inner="visibleSecrets.usernamePassword = !visibleSecrets.usernamePassword"
      />
      <v-btn type="submit" variant="outlined" prepend-icon="mdi-account-edit-outline" :loading="changingUsername">修改用户名</v-btn>
    </form>
  </section>
  <section class="settings-section">
    <div class="section-heading"><div><h2>管理员密码</h2><p>修改后所有现有登录令牌立即失效</p></div></div>
    <form class="password-form" @submit.prevent="changePassword">
      <v-text-field
        v-model="passwordForm.currentPassword"
        label="当前密码"
        :type="visibleSecrets.currentPassword ? 'text' : 'password'"
        autocomplete="current-password"
        :append-inner-icon="visibleSecrets.currentPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
        @click:append-inner="visibleSecrets.currentPassword = !visibleSecrets.currentPassword"
      />
      <v-text-field
        v-model="passwordForm.newPassword"
        label="新密码"
        :type="visibleSecrets.newPassword ? 'text' : 'password'"
        autocomplete="new-password"
        :append-inner-icon="visibleSecrets.newPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
        @click:append-inner="visibleSecrets.newPassword = !visibleSecrets.newPassword"
      />
      <v-text-field
        v-model="passwordForm.confirmPassword"
        label="确认新密码"
        :type="visibleSecrets.confirmPassword ? 'text' : 'password'"
        autocomplete="new-password"
        :append-inner-icon="visibleSecrets.confirmPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
        @click:append-inner="visibleSecrets.confirmPassword = !visibleSecrets.confirmPassword"
      />
      <v-btn type="submit" variant="outlined" prepend-icon="mdi-lock-reset" :loading="changingPassword">修改密码</v-btn>
    </form>
  </section>
</template>

<style scoped>
.settings-section { padding-block: 28px; border-bottom: 1px solid rgba(var(--v-theme-on-surface),.09); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 22px; }
.section-heading h2 { margin: 0; font-size: 1.08rem; letter-spacing: 0; }
.section-heading p { margin: 5px 0 0; color: rgba(var(--v-theme-on-surface),.56); font-size: .84rem; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 4px 16px; }
.switch-grid { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); margin-bottom: 10px; }
.password-form { display: grid; grid-template-columns: repeat(3, minmax(0,1fr)) auto; align-items: start; gap: 10px; }
.username-form { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)) auto; align-items: start; gap: 10px; }
@media (max-width: 820px) {
  .form-grid { grid-template-columns: 1fr; }
  .switch-grid { grid-template-columns: repeat(2, 1fr); }
  .password-form, .username-form { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 540px) {
  .section-heading { align-items: flex-start; flex-direction: column; }
  .switch-grid { grid-template-columns: 1fr; }
  .password-form, .username-form { grid-template-columns: 1fr; }
}
</style>
