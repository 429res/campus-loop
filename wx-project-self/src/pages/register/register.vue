<script setup>
import LoopInput from '../../components/LoopInput.vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import http from '../../common/http'

const form = reactive({ username:'', displayName:'', password:'', confirmPassword:'' })
const busy = ref(false), error = ref(''), uncertain = ref(false)
let redirect = 'profile'

onLoad(options => { if (['publish','demands'].includes(options.redirect)) redirect = options.redirect })
onUnload(clearPasswords)

function utf8Length(value) {
  let bytes = 0
  for (const character of value) {
    const code = character.codePointAt(0)
    bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4
  }
  return bytes
}

function clearPasswords() {
  form.password = ''
  form.confirmPassword = ''
}

function validate() {
  const username = form.username.trim()
  const displayName = form.displayName.trim()
  if (!/^[A-Za-z0-9_.-]{3,64}$/.test(username)) return '用户名须为 3–64 位字母、数字、下划线、点或连字符'
  if (!displayName || displayName.length > 64) return '显示名称须为 1–64 个字符'
  if (form.password.length < 12 || form.password.length > 64) return '请输入 12–64 个字符的密码'
  if (utf8Length(form.password) > 72) return '密码过长，请减少中文或特殊符号后重试'
  if (form.password !== form.confirmPassword) return '两次输入的密码不一致'
  return ''
}

function goLogin(reason = '') {
  clearPasswords()
  const params = [`redirect=${redirect}`]
  if (reason) params.push(`reason=${reason}`)
  uni.redirectTo({url:`/pages/login/login?${params.join('&')}`})
}

async function register() {
  if (busy.value || uncertain.value) return
  error.value = validate()
  if (error.value) return
  busy.value = true
  try {
    await http.post('/api/auth/register', {
      username: form.username.trim(),
      password: form.password,
      displayName: form.displayName.trim(),
    }, {silent:true, uncertainOnFailure:true})
    goLogin('registered')
  } catch (requestError) {
    if (requestError.uncertain) {
      uncertain.value = true
      clearPasswords()
      error.value = '未收到服务器响应，账号是否创建无法确认。请勿直接重复注册，先前往登录确认。'
    } else if (requestError.status === 403) {
      clearPasswords()
      error.value = '暂时无法注册，请稍后再试或使用已有账号登录。'
    } else if (requestError.status === 409) {
      clearPasswords()
      error.value = '该用户名已存在。请返回登录，或更换用户名后重新填写密码。'
    } else if (requestError.status === 400) {
      clearPasswords()
      error.value = requestError.message || '注册信息不符合要求，请检查后重新填写密码。'
    } else {
      clearPasswords()
      error.value = requestError.message
    }
  } finally { busy.value = false }
}
</script>

<template>
  <LoopLayout>
    <view class="register-layout">
      <form class="cl-panel cl-form register-panel" @submit="register">
        <text class="cl-title">创建账号</text>
        <view class="cl-field"><text class="cl-field-title">用户名</text><LoopInput v-model="form.username" class="cl-input" aria-label="注册用户名" placeholder="3–64 位字母、数字、_ . -" maxlength="64" autocomplete="username" :aria-invalid="!!error" :disabled="busy || uncertain" /></view>
        <view class="cl-field"><text class="cl-field-title">显示名称</text><LoopInput v-model="form.displayName" class="cl-input" aria-label="显示名称" placeholder="其他同学看到的名称" maxlength="64" autocomplete="name" :aria-invalid="!!error" :disabled="busy || uncertain" /></view>
        <view class="cl-field"><text class="cl-field-title">密码</text><LoopInput v-model="form.password" class="cl-input" aria-label="注册密码" placeholder="12–64 个字符" password maxlength="64" autocomplete="new-password" :aria-invalid="!!error" :disabled="busy || uncertain" /></view>
        <view class="cl-field"><text class="cl-field-title">确认密码</text><LoopInput v-model="form.confirmPassword" class="cl-input" aria-label="确认注册密码" placeholder="再次输入密码" password maxlength="64" autocomplete="new-password" :aria-invalid="!!error" :disabled="busy || uncertain" confirm-type="done" @confirm="register" /></view>
        <text v-if="error" class="cl-error" role="alert">{{ error }}</text>
        <LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :disabled="busy || uncertain" :loading="busy">{{ busy ? '正在创建…' : '创建账号' }}</LoopButton>
        <LoopButton v-if="uncertain" class="cl-btn cl-btn--wide" @click="goLogin('registration-unknown')">前往登录确认</LoopButton>
        <LoopButton v-else class="cl-btn cl-btn--wide" :disabled="busy" @click="goLogin()">返回登录</LoopButton>
      </form>
    </view>
  </LoopLayout>
</template>

<style scoped>
.register-layout{max-width:460px;margin:45px auto 70px}.register-panel{padding:32px}.register-panel>.cl-title{margin-bottom:8px}@media(max-width:760px){.register-layout{margin:24px auto 44px}.register-panel{padding:24px}}@media(max-width:430px){.register-panel{padding:20px}}
</style>
