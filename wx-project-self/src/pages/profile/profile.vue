<script setup>
import LoopIcon from '../../components/LoopIcon.vue'
import LoopInput from '../../components/LoopInput.vue'
import LoopButton from '../../components/LoopButton.vue'
import { reactive, ref } from 'vue'
import { onShow, onHide } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import http, { TOKEN_KEY, USER_KEY, clearSession } from '../../common/http'
import { showAppModal } from '../../common/modal'

const user = ref(null), loading = ref(false), error = ref('')
const profileBusy = ref(false), profileError = ref(''), profileSuccess = ref(''), profileUncertain = ref(false)
const passwordBusy = ref(false), passwordError = ref(''), showPasswordForm = ref(false)
const profileForm = reactive({ displayName:'' })
const passwordForm = reactive({ currentPassword:'', newPassword:'', confirmPassword:'' })
let loadedToken = '', readSequence = 0
const sameSession = token => !!token && token === uni.getStorageSync(TOKEN_KEY)
function resetAccount() {
  user.value = null; profileForm.displayName = ''; profileUncertain.value = false
  profileError.value = ''; profileSuccess.value = ''; passwordError.value = ''
  profileBusy.value = false; passwordBusy.value = false; clearPasswords(); showPasswordForm.value = false
}

const login = reason => uni.navigateTo({url:`/pages/login/login${reason ? `?reason=${reason}` : ''}`})
const showDeveloperTools = process.env.NODE_ENV !== 'production'
const gallery = () => uni.navigateTo({url:'/pages/controls/controls'})
const publish = () => uni.switchTab({url:'/pages/publish/publish'})
const myItems = () => uni.navigateTo({url:'/pages/my-items/my-items'})
const favorites = () => uni.navigateTo({url:'/pages/favorites/favorites'})
const exchanges = () => uni.navigateTo({url:'/pages/exchanges/exchanges'})
const governance = () => uni.navigateTo({url:'/pages/governance/governance'})
const demands = () => uni.navigateTo({url:'/pages/demands/demands'})

function applyUser(value) {
  user.value = value
  profileForm.displayName = value.displayName || ''
  uni.setStorageSync(USER_KEY,value)
}

async function load({ recovery = false } = {}) {
  const token = uni.getStorageSync(TOKEN_KEY), current = ++readSequence
  if (token !== loadedToken) { resetAccount(); loadedToken = token }
  error.value = ''; profileError.value = ''; profileSuccess.value = ''
  if (!token) { loading.value = false; resetAccount(); return }
  loading.value = true
  try {
    const loaded = await http.get('/api/auth/me',{}, {silent:true})
    if (!sameSession(token) || current !== readSequence) return
    applyUser(loaded)
    if (recovery) { profileUncertain.value = false; profileSuccess.value = '已从服务器重新读取当前资料。' }
  } catch(e) {
    if (current !== readSequence || (token !== uni.getStorageSync(TOKEN_KEY) && e.status !== 401)) return
    if (e.status === 401 && uni.getStorageSync(TOKEN_KEY) && !sameSession(token)) return
    error.value = e.message
    if (e.status === 401) { user.value = null; clearPasswords(); showPasswordForm.value = false }
  } finally { if (current === readSequence) loading.value = false }
}

async function saveProfile() {
  if (profileBusy.value || profileUncertain.value || !user.value) return
  const token = uni.getStorageSync(TOKEN_KEY)
  if (!sameSession(token)) return
  readSequence++
  profileError.value = ''; profileSuccess.value = ''
  const displayName = profileForm.displayName.trim()
  if (!displayName || displayName.length > 64) { profileError.value = '显示名称须为 1–64 个字符'; return }
  if (displayName === user.value.displayName) { profileSuccess.value = '显示名称没有变化。'; return }
  profileBusy.value = true
  try {
    const updated = await http.patch('/api/auth/me',{displayName},{silent:true,uncertainOnFailure:true})
    if (!sameSession(token)) return
    applyUser(updated)
    profileSuccess.value = '显示名称已保存并从服务器回读。'
  } catch(e) {
    if (token !== uni.getStorageSync(TOKEN_KEY) && (e.status !== 401 || uni.getStorageSync(TOKEN_KEY))) return
    profileError.value = e.uncertain ? '未收到服务器响应，保存结果无法确认。请先查询当前资料，不要直接重复提交。' : e.message
    profileUncertain.value = !!e.uncertain
    if (e.status === 401) { user.value = null; login('session-expired') }
  } finally { if (loadedToken === token) profileBusy.value = false }
}

function utf8Length(value) {
  let bytes = 0
  for (const character of value) {
    const code = character.codePointAt(0)
    bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4
  }
  return bytes
}

function clearPasswords() {
  passwordForm.currentPassword = ''; passwordForm.newPassword = ''; passwordForm.confirmPassword = ''
}

function togglePasswordForm() {
  if (passwordBusy.value) return
  showPasswordForm.value = !showPasswordForm.value
  passwordError.value = ''
  if (!showPasswordForm.value) clearPasswords()
}

async function changePassword() {
  if (passwordBusy.value) return
  const token = uni.getStorageSync(TOKEN_KEY)
  if (!sameSession(token)) return
  passwordError.value = ''
  if (!passwordForm.currentPassword) { passwordError.value = '请输入旧密码'; return }
  if (passwordForm.currentPassword.length > 72) { passwordError.value = '旧密码长度不能超过 72 个字符'; return }
  if (passwordForm.newPassword.length < 12 || passwordForm.newPassword.length > 64 || utf8Length(passwordForm.newPassword) > 72) {
    passwordError.value = utf8Length(passwordForm.newPassword) > 72 ? '密码过长，请减少中文或特殊符号后重试' : '请输入 12–64 个字符的新密码'; return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) { passwordError.value = '两次输入的新密码不一致'; return }
  passwordBusy.value = true
  const confirmation = await showAppModal({title:'确认修改密码',content:'成功后本账号所有设备都会退出，需要使用新密码重新登录。'})
  if (!confirmation.confirm || !sameSession(token)) { if (loadedToken === token) passwordBusy.value = false; return }
  try {
    await http.post('/api/auth/password',{currentPassword:passwordForm.currentPassword,newPassword:passwordForm.newPassword},{silent:true,uncertainOnFailure:true})
    if (!sameSession(token)) return
    readSequence++; clearPasswords(); clearSession(); user.value = null
    login('password-changed')
  } catch(e) {
    if (token !== uni.getStorageSync(TOKEN_KEY) && (e.status !== 401 || uni.getStorageSync(TOKEN_KEY))) return
    clearPasswords()
    if (e.uncertain) {
      clearSession(); user.value = null
      login('password-unknown')
    } else if (e.status === 401) {
      user.value = null; login('session-expired')
    } else {
      passwordError.value = e.message
    }
  } finally { if (loadedToken === token) passwordBusy.value = false }
}

function logout() {
  const token = uni.getStorageSync(TOKEN_KEY)
  showAppModal({title:'退出登录',content:'确定退出当前账号吗？',danger:true,async success(result){
    if(!result.confirm || !sameSession(token)) return
    try {await http.post('/api/auth/logout',{})} catch(e) {if(e.status !== 401) return}
    if (token !== uni.getStorageSync(TOKEN_KEY) && uni.getStorageSync(TOKEN_KEY)) return
    readSequence++; clearPasswords();showPasswordForm.value=false;clearSession();user.value=null
  }})
}

onShow(load)
onHide(() => { readSequence++; clearPasswords(); showPasswordForm.value = false })
</script>

<template>
  <LoopLayout active-tab="profile">
    <view class="cl-page-heading"><text class="cl-title">我的循环</text></view>
    <view v-if="loading" class="cl-empty"><text class="cl-label">正在读取账号…</text></view>
    <view v-else-if="error" class="cl-panel cl-empty" role="alert"><text class="cl-error">{{ error }}</text><LoopButton class="cl-btn" @click="load">重试</LoopButton><LoopButton class="cl-btn" @click="login('session-expired')">重新登录</LoopButton></view>
    <template v-else-if="user">
      <view class="profile-card cl-panel"><view class="profile-avatar">{{ user.displayName?.slice(0,1) || '同' }}</view><view class="profile-copy"><text class="profile-name">{{ user.displayName }}</text><text class="cl-subtitle">@{{ user.username }} · {{ user.role === 'ADMIN' ? '管理员' : '校园用户' }}</text></view><LoopButton class="cl-btn" @click="logout">退出登录</LoopButton></view>
      <view class="account-grid">
        <form class="cl-panel cl-form" @submit="saveProfile">
          <view><text class="cl-section-title">个人资料</text></view>
          <view class="cl-field"><text class="cl-field-title">用户名</text><LoopInput class="cl-input" :model-value="user.username" aria-label="用户名（不可修改）" disabled /></view>
          <view class="cl-field"><text class="cl-field-title">显示名称</text><LoopInput v-model="profileForm.displayName" class="cl-input" aria-label="显示名称" :aria-invalid="!!profileError" maxlength="64" :disabled="profileBusy || profileUncertain" /></view>
          <text v-if="profileError" class="cl-error" role="alert">{{ profileError }}</text><text v-if="profileSuccess" class="cl-success" role="status">{{ profileSuccess }}</text>
          <view class="form-actions"><LoopButton class="cl-btn cl-btn--primary" form-type="submit" :disabled="profileBusy || profileUncertain">{{ profileBusy ? '保存中…' : '保存显示名称' }}</LoopButton><LoopButton v-if="profileUncertain" class="cl-btn" :disabled="loading" @click="load({recovery:true})">查询当前资料</LoopButton></view>
        </form>
        <view class="cl-panel password-panel">
          <view class="section-row"><view><text class="cl-section-title">账号密码</text></view><LoopButton class="cl-btn" :disabled="passwordBusy" @click="togglePasswordForm">{{ showPasswordForm ? '收起' : '修改密码' }}</LoopButton></view>
          <form v-if="showPasswordForm" class="cl-form password-form" @submit="changePassword">
            <view class="cl-field"><text class="cl-field-title">旧密码</text><LoopInput v-model="passwordForm.currentPassword" class="cl-input" aria-label="旧密码" password maxlength="72" :aria-invalid="!!passwordError" :disabled="passwordBusy" autocomplete="current-password" /></view>
            <view class="cl-field"><text class="cl-field-title">新密码</text><LoopInput v-model="passwordForm.newPassword" class="cl-input" aria-label="新密码" placeholder="12–64 个字符" password maxlength="64" :aria-invalid="!!passwordError" :disabled="passwordBusy" autocomplete="new-password" /></view>
            <view class="cl-field"><text class="cl-field-title">确认新密码</text><LoopInput v-model="passwordForm.confirmPassword" class="cl-input" aria-label="确认新密码" password maxlength="64" :aria-invalid="!!passwordError" :disabled="passwordBusy" autocomplete="new-password" confirm-type="done" @confirm="changePassword" /></view>
            <text v-if="passwordError" class="cl-error" role="alert">{{ passwordError }}</text>
            <LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :disabled="passwordBusy">{{ passwordBusy ? '修改中…' : '确认修改密码' }}</LoopButton>
          </form>
        </view>
      </view>
      <view class="profile-actions"><LoopButton class="cl-panel profile-action" @click="publish"><view class="profile-action-icon"><LoopIcon name="plus" tone="primary"/></view><text class="profile-action-title">发布我的闲置</text><text class="cl-hint">物品与需求一起发布</text><LoopIcon class="profile-action-arrow" name="arrow" :size="18"/></LoopButton><LoopButton class="cl-panel profile-action" @click="demands"><view class="profile-action-icon"><LoopIcon name="target" tone="primary"/></view><text class="profile-action-title">我的需求</text><text class="cl-hint">独立管理想要与可提供物品</text><LoopIcon class="profile-action-arrow" name="arrow" :size="18"/></LoopButton><LoopButton class="cl-panel profile-action" @click="favorites"><view class="profile-action-icon"><LoopIcon name="heart" tone="primary"/></view><text class="profile-action-title">我的收藏</text><text class="cl-hint">查看收藏的物品</text><LoopIcon class="profile-action-arrow" name="arrow" :size="18"/></LoopButton><LoopButton class="cl-panel profile-action" @click="exchanges"><view class="profile-action-icon"><LoopIcon name="exchange" tone="primary"/></view><text class="profile-action-title">我的交换</text><text class="cl-hint">查看交换进度</text><LoopIcon class="profile-action-arrow" name="arrow" :size="18"/></LoopButton><LoopButton class="cl-panel profile-action" @click="governance"><view class="profile-action-icon"><LoopIcon name="alert" tone="primary"/></view><text class="profile-action-title">举报与争议</text><text class="cl-hint">查看举报与处理进度</text><LoopIcon class="profile-action-arrow" name="arrow" :size="18"/></LoopButton><LoopButton v-if="showDeveloperTools" class="cl-panel profile-action" @click="gallery"><view class="profile-action-icon"><LoopIcon name="grid" tone="primary"/></view><text class="profile-action-title">控件实验室</text><text class="cl-hint">共同维护的视觉与交互规范</text><LoopIcon class="profile-action-arrow" name="arrow" :size="18"/></LoopButton></view>
    </template>
    <view v-else class="cl-panel cl-empty"><LoopIcon name="user" tone="primary" :size="36"/><text>登录后管理个人资料</text><text class="cl-hint">登录后可查看自己的物品、需求和交换进度。</text><LoopButton class="cl-btn cl-btn--primary" @click="login()">登录</LoopButton></view>
    <view class="cl-section-heading"><text class="cl-section-title">我的物品</text><text class="cl-tag cl-tag--muted">审核进度</text></view><view class="cl-panel cl-empty"><LoopButton class="cl-btn" @click="myItems">查看我的物品</LoopButton><text class="cl-hint">查看物品状态和流转记录。</text></view>
  </LoopLayout>
</template>

<style scoped>
.profile-card{display:flex;align-items:center;gap:20px;padding:30px}.profile-avatar{width:76px;height:76px;border-radius:25px;background:var(--cl-primary-soft);color:var(--cl-primary);display:flex;align-items:center;justify-content:center;font-size:32px;font-weight:700;flex-shrink:0}.profile-copy{flex:1;min-width:0}.profile-name{display:block;font-size:22px;font-weight:750;margin-bottom:6px}.account-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin-top:22px;align-items:start}.section-hint{display:block;margin-top:8px}.section-row,.form-actions{display:flex;align-items:flex-start;justify-content:space-between;gap:14px}.password-panel{display:flex;flex-direction:column;gap:22px}.password-form{padding-top:4px}.cl-success{display:block;color:var(--cl-success);font-size:13px;line-height:1.6}.profile-actions{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin-top:22px}.profile-action{position:relative;color:var(--cl-text);text-align:left;display:flex;flex-direction:column;align-items:flex-start;gap:9px;width:100%}.profile-action-icon{display:flex;align-items:center;justify-content:center;width:38px;height:38px;background:var(--cl-primary-soft);color:var(--cl-primary);border-radius:12px;font-size:25px;margin-bottom:5px}.profile-action-icon.blue{color:var(--cl-blue);background:var(--cl-blue-soft)}.profile-action-title{font-size:17px;font-weight:700}.profile-action-arrow{position:absolute;top:24px;right:24px;color:var(--cl-muted)}

@media(max-width:760px){.account-grid{grid-template-columns:1fr}.profile-card{padding:22px;gap:12px;flex-wrap:wrap}.profile-avatar{width:54px;height:54px;border-radius:19px;font-size:25px}.profile-name{font-size:20px}.profile-copy .cl-subtitle{font-size:11px}.profile-card>.cl-btn{margin-left:66px}.profile-actions{gap:12px}.profile-action{padding:17px}.profile-action-title{font-size:14px}.profile-action .cl-hint{font-size:10px}.section-row{align-items:center}}

@media(max-width:430px){.profile-actions{grid-template-columns:1fr}.form-actions{align-items:stretch;flex-direction:column}.form-actions .cl-btn{width:100%}}
 .profile-action{display:grid;grid-template-columns:44px minmax(0,1fr) 20px;grid-template-rows:auto auto;column-gap:14px;row-gap:5px;align-items:center;padding:22px}.profile-action-icon{grid-column:1;grid-row:1 / 3;width:44px;height:44px;margin:0;border-radius:14px}.profile-action-title{grid-column:2;grid-row:1;line-height:1.5}.profile-action .cl-hint{grid-column:2;grid-row:2;font-size:12px;line-height:1.6}.profile-action-arrow{position:static;grid-column:3;grid-row:1 / 3}.profile-name,.profile-copy .cl-subtitle{overflow-wrap:anywhere}.section-row{flex-wrap:wrap}.section-row>view{min-width:0;flex:1 1 180px}
@media(max-width:760px){.profile-actions{grid-template-columns:1fr}.profile-action{padding:18px}.profile-card>.cl-btn{margin-left:0}.profile-copy{min-width:140px}.profile-action-title{font-size:16px}}

</style>
