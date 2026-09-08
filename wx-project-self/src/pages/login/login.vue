<script setup>
import LoopButton from '../../components/LoopButton.vue'
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import http, { TOKEN_KEY, USER_KEY } from '../../common/http'
const username = ref(''), password = ref(''), busy = ref(false), error = ref(''), notice = ref('')
let redirect = 'profile'
onLoad(options => {
  if (options.redirect === 'publish') redirect = 'publish'
  if (options.reason === 'registered') notice.value = '账号已创建，但尚未登录。请使用刚才填写的用户名和密码登录。'
  if (options.reason === 'registration-unknown') notice.value = '注册请求结果无法确认。请先尝试登录；若无法登录，请确认服务状态后再决定是否重新注册。'
})
const register = () => uni.navigateTo({url:`/pages/register/register?redirect=${redirect}`})
async function login() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) { error.value = '请填写用户名和密码'; return }
  busy.value = true
  try {
    const result = await http.post('/api/auth/login', {username:username.value.trim(),password:password.value},{silent:true})
    uni.setStorageSync(TOKEN_KEY,result.token); uni.setStorageSync(USER_KEY,result.user); password.value = ''
    uni.switchTab({url:`/pages/${redirect}/${redirect}`})
  } catch(e) { error.value = e.message } finally { busy.value = false }
}
</script>
<template>
  <LoopLayout>
    <view class="login-layout">
      <view class="login-copy"><text class="login-kicker">WELCOME BACK</text><text class="login-title">好物的下一站，<br/>从这里开始。</text><text class="cl-subtitle">把闲置交给需要的人，<br/>也找到你的校园小确幸。</text><image src="/static/demo/book.svg" mode="aspectFit" class="login-art"/></view>
      <view class="cl-panel login-panel">
        <text class="cl-title">登录 Campus Loop</text><text class="cl-subtitle login-intro">使用已有账号登录</text><text v-if="notice" class="cl-notice login-notice" role="status">{{ notice }}</text>
        <form class="cl-form" @submit="login">
          <view class="cl-field"><text class="cl-field-title">用户名</text><input v-model="username" class="cl-input" aria-label="用户名" placeholder="输入用户名" :aria-invalid="!!error" :disabled="busy" maxlength="64" autocomplete="username" /></view>
          <view class="cl-field"><text class="cl-field-title">密码</text><input v-model="password" class="cl-input" aria-label="密码" placeholder="输入密码" password :disabled="busy" maxlength="72" autocomplete="current-password" confirm-type="done" @confirm="login" /></view>
          <text v-if="error" class="cl-error" role="alert">{{ error }}</text>
          <LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :disabled="busy" :loading="busy">{{ busy ? '登录中…' : '登录，开始循环' }}</LoopButton>
          <view class="register-entry"><text class="cl-hint">本地开发环境可由服务端显式开放自助注册，不代表已完成校园身份核验。</text><LoopButton class="cl-btn cl-btn--wide" :disabled="busy" @click="register">创建开发账号</LoopButton></view>
          <text class="cl-hint">找回密码及微信授权将在后续阶段实现。</text>
        </form>
      </view>
    </view>
  </LoopLayout>
</template>
<style scoped>
.login-layout{display:grid;grid-template-columns:1fr 1fr;gap:70px;max-width:930px;margin:55px auto 70px;align-items:center}.login-copy{padding:10px 20px}.login-kicker{font-size:11px;letter-spacing:3px;color:var(--cl-primary)}.login-title{display:block;font-size:36px;font-weight:800;line-height:1.55;margin:20px 0}.login-art{width:240px;height:180px;border-radius:20px;margin-top:20px;transform:rotate(-6deg)}.login-panel{padding:36px}.login-intro{margin:12px 0 20px;font-size:13px}.login-notice{display:block;margin-bottom:20px}.register-entry{display:flex;flex-direction:column;gap:10px;padding-top:4px;border-top:1px solid var(--cl-border)}@media(max-width:700px){.login-layout{grid-template-columns:1fr;gap:24px;margin:24px auto 40px;max-width:420px}.login-copy{display:none}.login-panel{padding:25px}.login-panel .cl-title{font-size:23px}}
</style>
