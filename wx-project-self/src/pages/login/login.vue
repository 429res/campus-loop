<script setup>
import LoopInput from '../../components/LoopInput.vue'
import LoopButton from '../../components/LoopButton.vue'
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import http, { TOKEN_KEY, USER_KEY } from '../../common/http'
const openRegister=()=>uni.navigateTo({url:'/pages/register/register'})
const username = ref(''), password = ref(''), busy = ref(false), error = ref(''), notice = ref('')
let redirect = 'profile', exchangeId = ''
onLoad(options => {
  if (['publish','demands','matches','favorites','exchanges'].includes(options.redirect)) redirect = options.redirect
  if (/^[1-9][0-9]*$/.test(options.exchangeId || '') && Number.isSafeInteger(Number(options.exchangeId))) exchangeId = options.exchangeId
  if (options.reason === 'registered') notice.value = '账号已创建，请登录。'
  if (options.reason === 'password-changed') notice.value = '密码已修改，所有旧会话已退出。请使用新密码重新登录。'
  if (options.reason === 'password-unknown') notice.value = '改密请求结果无法确认。请先尝试新密码登录；若未生效，再使用旧密码。'
  if (options.reason === 'session-expired') notice.value = '登录已过期，请重新登录。'
})
async function login() {
  if (busy.value) return
  error.value = ''
  if (!username.value.trim() || !password.value) { error.value = '请填写用户名和密码'; return }
  busy.value = true
  try {
    const result = await http.post('/api/auth/login', {username:username.value.trim(),password:password.value},{silent:true})
    uni.setStorageSync(TOKEN_KEY,result.token); uni.setStorageSync(USER_KEY,result.user); password.value = ''
    if (['demands','favorites','exchanges'].includes(redirect)) uni.redirectTo({url:`/pages/${redirect}/${redirect}${redirect==='exchanges' && exchangeId ? `?id=${exchangeId}` : ''}`})
    else uni.switchTab({url:`/pages/${redirect}/${redirect}`})
  } catch(e) { error.value = e.message } finally { busy.value = false }
}
</script>
<template>
  <LoopLayout><view class="login-layout"><view class="login-copy"><text class="login-kicker">WELCOME BACK</text><text class="login-title">好物的下一站，<br/>从这里开始。</text><text class="cl-subtitle">把闲置交给需要的人，<br/>也找到你的校园小确幸。</text><image src="/static/demo/book.svg" mode="aspectFit" class="login-art"/></view><view class="cl-panel login-panel"><text class="cl-title">登录 Campus Loop</text><text class="cl-subtitle login-intro">使用已有账号登录</text><text v-if="notice" class="cl-notice login-notice" role="status">{{ notice }}</text><form class="cl-form" @submit="login"><view class="cl-field"><text class="cl-field-title">用户名</text><LoopInput v-model="username" class="cl-input" aria-label="用户名" placeholder="输入用户名" :aria-invalid="!!error" :disabled="busy" maxlength="64" /></view><view class="cl-field"><text class="cl-field-title">密码</text><LoopInput v-model="password" class="cl-input" aria-label="密码" placeholder="输入密码" password :disabled="busy" maxlength="128" confirm-type="done" @confirm="login" /></view><text v-if="error" class="cl-error" role="alert">{{ error }}</text><LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :disabled="busy" :loading="busy">{{ busy ? '登录中…' : '登录，开始循环' }}</LoopButton><text class="cl-hint">开发自助注册由维护者开放；找回密码和微信授权尚未提供。</text><LoopButton class="cl-btn cl-btn--wide" :disabled="busy" @click="openRegister">创建开发账号</LoopButton></form></view></view></LoopLayout>
</template>
<style scoped>
.login-layout{display:grid;grid-template-columns:1fr 1fr;gap:70px;max-width:930px;margin:55px auto 70px;align-items:center}.login-copy{padding:10px 20px}.login-kicker{font-size:11px;letter-spacing:3px;color:var(--cl-primary)}.login-title{display:block;font-size:36px;font-weight:800;line-height:1.55;margin:20px 0}.login-art{width:240px;height:180px;border-radius:20px;margin-top:20px;transform:rotate(-6deg)}.login-panel{padding:36px}.login-intro{margin:12px 0 20px;font-size:13px}.login-notice{display:block;margin-bottom:20px}@media(max-width:700px){.login-layout{grid-template-columns:1fr;gap:24px;margin:24px auto 40px;max-width:420px}.login-copy{display:none}.login-panel{padding:25px}.login-panel .cl-title{font-size:23px}}
</style>
