<script setup>
import LoopIcon from '../../components/LoopIcon.vue'
import LoopPicker from '../../components/LoopPicker.vue'
import LoopButton from '../../components/LoopButton.vue'
import { ref, watch } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import http, { TOKEN_KEY, USER_KEY, imageUrl, isAbortError } from '../../common/http'
const categories = ref([]), loggedIn = ref(false), verifyingSession = ref(false), busy = ref(false), uploading = ref(false), error = ref(''), categoriesError = ref('')
const uploadProgress=ref(0),submitted=ref(null)
const uploadError = ref(''), pendingFile = ref(''), draftNotice = ref(''), sessionError = ref(''), removedUpload = ref(false), currentUser = ref(null)
const empty = () => ({title:'',description:'',categoryId:'',conditionLevel:3,tags:'',wantedCategoryId:'',wantedTags:'',imageUrl:''})
const form = ref(empty())
const conditionNames = ['有使用痕迹','正常使用','成色良好','几乎全新','全新未用']
let uploadRequest, uploadAttempt = 0, hydratedUserId = null
let verificationAttempt = 0, categoriesAttempt = 0, loadedCategoriesAttempt = 0, accountGeneration = 0, verifiedToken = ''
const draftKey = id => `campus-loop-publish-draft-${id}`
const hasDraftContent = value => value.title || value.description || value.categoryId || value.tags || value.wantedCategoryId || value.wantedTags || value.imageUrl
function clearAccountState() {
  accountGeneration++
  loggedIn.value = false; currentUser.value = null; verifiedToken = ''; hydratedUserId = null
  // Invalidate callbacks before aborting: abort may settle the old request immediately.
  uploadAttempt++
  uploadRequest?.abort?.(); uploadRequest = null
  busy.value = false; uploading.value = false
  form.value = empty(); submitted.value=null; pendingFile.value = ''; uploadError.value = ''; draftNotice.value = ''; removedUpload.value = false
}
const ownsAccountState = scope => scope && scope.generation === accountGeneration && scope.token === verifiedToken && scope.userId === currentUser.value?.id
const isCurrentAccount = scope => ownsAccountState(scope) && scope.token === uni.getStorageSync(TOKEN_KEY)
function captureAccount() {
  if (!loggedIn.value || verifyingSession.value || !currentUser.value) return null
  if (verifiedToken !== uni.getStorageSync(TOKEN_KEY)) { clearAccountState(); return null }
  return {generation:accountGeneration, token:verifiedToken, userId:currentUser.value.id}
}
function requestFailed(e, scope, target) {
  if (!ownsAccountState(scope)) return
  const token = uni.getStorageSync(TOKEN_KEY)
  if (scope.token !== token || e.status === 401) {
    clearAccountState()
    if (e.status === 401 && (!token || token === scope.token)) error.value = e.message
    return
  }
  if (!isAbortError(e)) target.value = e.message
}
function restoreDraft(user) {
  if (hydratedUserId === user.id) return
  form.value = empty(); pendingFile.value = ''; uploadError.value = ''; draftNotice.value = ''
  const saved = uni.getStorageSync(draftKey(user.id))
  if (saved?.form && typeof saved.form === 'object') {
    form.value = {...empty(), ...saved.form}
    draftNotice.value = form.value.imageUrl
      ? '已恢复上次填写的内容和照片。'
      : '已恢复上次填写的内容。'
  }
  hydratedUserId = user.id
}
watch(form, value => {
  if (!currentUser.value || hydratedUserId !== currentUser.value.id || verifiedToken !== uni.getStorageSync(TOKEN_KEY)) return
  if (hasDraftContent(value)) uni.setStorageSync(draftKey(currentUser.value.id), {form:{...value},savedAt:Date.now()})
  else uni.removeStorageSync(draftKey(currentUser.value.id))
}, {deep:true, flush:'sync'})
async function loadCategories() {
  const attempt = ++categoriesAttempt
  categoriesError.value = ''
  try {
    const loaded = await http.get('/api/categories',{}, {silent:true})
    if (attempt !== categoriesAttempt) return
    categories.value = loaded; loadedCategoriesAttempt = attempt
    validateRestoredCategories()
  } catch (e) { if (attempt === categoriesAttempt && !isAbortError(e)) categoriesError.value = e.message }
}
function validateRestoredCategories() {
  if (!loadedCategoriesAttempt || loadedCategoriesAttempt !== categoriesAttempt) return
  let changed = false
  for (const key of ['categoryId','wantedCategoryId']) {
    if (form.value[key] && !categories.value.some(category => category.id === form.value[key])) { form.value[key] = ''; changed = true }
  }
  if (changed) draftNotice.value = '已恢复草稿；其中失效的分类已清除，请重新选择。'
}
async function verifySession() {
  const attempt = ++verificationAttempt
  const token = uni.getStorageSync(TOKEN_KEY)
  sessionError.value = ''; error.value = ''
  if (!token || token !== verifiedToken) clearAccountState()
  loggedIn.value = false; verifyingSession.value = !!token
  if (!token) return
  try {
    const user = await http.get('/api/auth/me',{}, {silent:true})
    if (attempt !== verificationAttempt) return
    if (token !== uni.getStorageSync(TOKEN_KEY)) { clearAccountState(); return }
    if (currentUser.value && currentUser.value.id !== user.id) clearAccountState()
    verifiedToken = token; currentUser.value = user
    uni.setStorageSync(USER_KEY,user); restoreDraft(user); loggedIn.value = true
  } catch (e) {
    if (attempt !== verificationAttempt) return
    const currentToken = uni.getStorageSync(TOKEN_KEY)
    clearAccountState()
    // The request wrapper clears an expired token; a replacement session belongs to another verification.
    if (token !== currentToken && (currentToken || e.status !== 401)) return
    if (e.status === 401) error.value = e.message
    else if (!isAbortError(e)) sessionError.value = e.message
  } finally { if (attempt === verificationAttempt) verifyingSession.value = false }
}
onShow(async () => {
  const session = verifySession()
  const attempt = verificationAttempt
  await Promise.all([loadCategories(), session])
  if (attempt === verificationAttempt) validateRestoredCategories()
})
onUnload(() => { verificationAttempt++; categoriesAttempt++; clearAccountState(); verifyingSession.value = false })
const login = () => uni.navigateTo({url:'/pages/login/login?redirect=publish'})
const categoryName = id => categories.value.find(c => c.id === id)?.name || '请选择分类'
const selectCategory = (event,key) => { if (captureAccount() && !busy.value) form.value[key] = categories.value[Number(event.detail.value)]?.id || '' }
async function startUpload(filePath, scope = captureAccount()) {
  if (!isCurrentAccount(scope) || busy.value || uploading.value) return
  const attempt = ++uploadAttempt
  uploading.value = true; uploadProgress.value=0; uploadError.value = ''; removedUpload.value = false
  try {
    uploadRequest = http.upload(filePath,{silent:true,onProgress:value=>{if(attempt===uploadAttempt&&isCurrentAccount(scope))uploadProgress.value=value}})
    const result = await uploadRequest
    if (attempt !== uploadAttempt || !isCurrentAccount(scope)) return
    form.value.imageUrl = result.url; pendingFile.value = ''; uploadProgress.value=100; uni.showToast({title:'图片上传成功',icon:'success'})
  } catch(e) {
    if (attempt === uploadAttempt) requestFailed(e, scope, uploadError)
  } finally {
    if (attempt === uploadAttempt && ownsAccountState(scope)) { uploading.value = false; uploadRequest = null }
  }
}
function pickImage() {
  const scope = captureAccount()
  if (!scope || uploading.value || busy.value) return
  uni.chooseImage({count:1,sizeType:['compressed'],sourceType:['album','camera'],success(res) {
    if (!isCurrentAccount(scope) || busy.value || uploading.value || !res.tempFilePaths?.[0]) return
    pendingFile.value = res.tempFilePaths[0]; form.value.imageUrl = ''; startUpload(pendingFile.value, scope)
  }})
}
function cancelUpload() {
  if (!uploading.value) return
  uploadAttempt++
  uploadRequest?.abort?.(); uploadRequest = null
  uploading.value = false; uploadError.value = '上传已取消，可保留预览后重试。'
}
function removeImage() {
  if (!captureAccount() || busy.value) return
  cancelUpload()
  removedUpload.value = !!form.value.imageUrl
  pendingFile.value = ''; form.value.imageUrl = ''; uploadError.value = ''
}
async function publish() {
  const scope = captureAccount()
  if (!scope || busy.value || uploading.value) return
  error.value = ''
  if (!form.value.title.trim() || !form.value.description.trim() || !form.value.categoryId || !form.value.wantedCategoryId) { error.value = '请填写标题、物品描述、物品分类和想换的分类'; return }
  const tags = value => [...new Set(value.split(/[,，]/).map(t => t.trim()).filter(Boolean))]
  const itemTags = tags(form.value.tags), wantedTags = tags(form.value.wantedTags)
  if (itemTags.length > 8 || wantedTags.length > 8 || [...itemTags,...wantedTags].some(tag => tag.length > 20)) { error.value = '每组最多 8 个标签，每个标签最多 20 个字'; return }
  busy.value = true
  try {
    const data = await http.post('/api/items', {...form.value,title:form.value.title.trim(),description:form.value.description.trim(),tags:itemTags,wantedTags},{silent:true})
    if (!isCurrentAccount(scope)) return
    uni.removeStorageSync(draftKey(scope.userId)); form.value = empty(); pendingFile.value='';uploadProgress.value=0;uploadError.value=''
    submitted.value=data; uni.showToast({title:'提交成功',icon:'success'})
  } catch(e) { requestFailed(e, scope, error) } finally { if (ownsAccountState(scope)) busy.value = false }
}
const openMatches = () => uni.switchTab({url:'/pages/matches/matches'})
const openSubmitted = () => uni.navigateTo({url:`/pages/detail/detail?id=${submitted.value.id}&mine=1`})
</script>
<template>
  <LoopLayout active-tab="publish">
    <view class="cl-page-heading"><text class="cl-title">让闲置，开始下一段旅程</text><text class="cl-subtitle">写下你有什么、想换什么，审核通过后自动为你寻找交换方案。</text></view>
    <view v-if="verifyingSession" class="cl-panel cl-empty" role="status"><text>正在加载…</text></view>
    <view v-else-if="!loggedIn" class="cl-panel cl-empty"><text class="cl-empty-symbol">↗</text><text>{{ error || sessionError || '登录后发布你的闲置' }}</text><LoopButton v-if="sessionError" class="cl-btn" @click="verifySession">重试验证</LoopButton><LoopButton class="cl-btn cl-btn--primary" @click="login">{{ error ? '重新登录' : '登录' }}</LoopButton></view>
    <view v-else-if="!submitted" class="publish-layout">
      <form class="cl-panel cl-form" @submit="publish">
        <view v-if="draftNotice" class="cl-notice" role="status">{{ draftNotice }}</view>
        <view v-if="categoriesError" class="inline-error" role="alert"><text class="cl-error">分类加载失败：{{ categoriesError }}</text><LoopButton class="cl-btn" @click="loadCategories">重试分类</LoopButton></view>
        <view class="cl-field"><text class="cl-field-title">物品标题 *</text><input v-model="form.title" class="cl-input" aria-label="物品标题" placeholder="例如：陪我度过大一的阅读台灯" maxlength="100" :disabled="busy" /></view>
        <view class="cl-grid-2"><view class="cl-field"><text class="cl-field-title">物品分类 *</text><LoopPicker :range="categories" range-key="name" :value="categories.findIndex(c=>c.id===form.categoryId)" aria-label="物品分类" :disabled="busy || !categories.length" @change="selectCategory($event,'categoryId')"><view class="cl-picker" aria-label="物品分类"><text>{{ categoryName(form.categoryId) }}</text><text>⌄</text></view></LoopPicker></view><view class="cl-field"><text class="cl-field-title">物品成色</text><LoopPicker :range="conditionNames" :value="form.conditionLevel - 1" :disabled="busy" @change="form.conditionLevel=Number($event.detail.value)+1" aria-label="物品成色"><view class="cl-picker" aria-label="物品成色"><text>{{ conditionNames[form.conditionLevel-1] }}</text><text>⌄</text></view></LoopPicker></view></view>
        <view class="cl-field"><text class="cl-field-title">物品描述 *</text><textarea v-model="form.description" class="cl-textarea" aria-label="物品描述" placeholder="介绍使用情况、尺寸和已知瑕疵，真实描述更容易匹配。" maxlength="2000" :disabled="busy" /></view>
        <view class="cl-field"><text class="cl-field-title">物品标签</text><input v-model="form.tags" class="cl-input" aria-label="物品标签" placeholder="用逗号分隔，最多 8 个，每个 20 字" maxlength="200" :disabled="busy" /></view>
        <view class="cl-divider"/><view class="cl-field"><text class="cl-section-title">我想换到</text><text class="cl-hint">选好想换的类别，再写几个关键词；不用另外创建需求。</text></view>
        <view class="cl-field"><text class="cl-field-title">想要的分类 *</text><LoopPicker :range="categories" range-key="name" :value="categories.findIndex(c=>c.id===form.wantedCategoryId)" aria-label="想要的分类" :disabled="busy || !categories.length" @change="selectCategory($event,'wantedCategoryId')"><view class="cl-picker" aria-label="想要的分类"><text>{{ categoryName(form.wantedCategoryId) }}</text><text>⌄</text></view></LoopPicker></view>
        <view class="cl-field"><text class="cl-field-title">想换什么（关键词）</text><input v-model="form.wantedTags" class="cl-input" aria-label="偏好标签" placeholder="例如：台灯，宿舍照明" maxlength="200" :disabled="busy" /></view>
        <text v-if="error" class="cl-error" role="alert">{{ error }}</text><LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :disabled="busy || uploading || !!categoriesError" :loading="busy">{{ busy ? '正在提交…' : uploading ? '请等待图片上传' : '提交审核' }}</LoopButton>
      </form>
      <view class="cl-stack"><view class="cl-panel photo-panel"><text class="cl-field-title">给物品拍张照片</text><view v-if="pendingFile || form.imageUrl" class="photo-preview"><image :src="pendingFile || imageUrl(form.imageUrl)" mode="aspectFill"/><LoopButton class="cl-icon-btn photo-remove" aria-label="移除照片引用" :disabled="busy" @click="removeImage"><LoopIcon name="close"/></LoopButton></view><LoopButton v-else class="upload-zone" :disabled="busy" @click="pickImage"><LoopIcon name="plus" tone="primary" :size="32"/><text>选择一张照片</text><text class="cl-hint">JPG / PNG / GIF / WebP，最大 5 MB</text></LoopButton><view v-if="uploading || uploadError" class="upload-actions"><text :class="uploadError ? 'cl-error' : 'cl-hint'">{{ uploading ? (uploadProgress>=100?'正在处理图片…':`图片上传中 ${uploadProgress}%`) : uploadError }}</text><LoopButton v-if="uploading" class="cl-btn" @click="cancelUpload">取消上传</LoopButton><LoopButton v-else-if="uploadError && pendingFile" class="cl-btn" @click="startUpload(pendingFile)">重试上传</LoopButton></view><text v-if="removedUpload" class="cl-hint" role="status">照片已移除。</text><view v-if="form.imageUrl&&!uploading&&!uploadError" class="upload-success" role="status"><text>✓ 图片上传成功</text><LoopButton class="cl-btn" :disabled="busy" @click="pickImage">更换照片</LoopButton></view></view><view class="cl-panel"><text class="cl-field-title">交换的一点小默契</text><text class="publish-tip">如实说明成色与瑕疵<br/>在校园公共区域交接<br/>正式确认前，物品仍可被发现</text></view></view>
    </view>
    <view v-if="loggedIn&&submitted" class="cl-panel publish-receipt" role="status"><text class="receipt-check">✓</text><text class="cl-title">提交成功，正在等待审核</text><text class="cl-subtitle">「{{submitted.title}}」通过审核后就会公开，你填写的求换需求已保存，会自动参与匹配。</text><view class="receipt-steps"><text>1 填写物品与需求 ✓</text><text>2 等待审核</text><text>3 查看匹配，邀请交换</text></view><LoopButton class="cl-btn cl-btn--primary" @click="openMatches">去交换灵感看看</LoopButton><LoopButton class="cl-btn" @click="openSubmitted">查看物品与审核进度</LoopButton><LoopButton class="cl-btn cl-btn--quiet" @click="submitted=null">继续发布</LoopButton></view>
  </LoopLayout>
</template>
<style scoped>
.upload-success{display:flex;align-items:center;justify-content:space-between;gap:12px;color:var(--cl-success,#287d5d);font-size:14px}.publish-receipt{max-width:600px;margin:30px auto;display:flex;flex-direction:column;gap:20px;text-align:center}.receipt-check{font-size:38px;color:var(--cl-success,#287d5d)}.receipt-steps{display:flex;flex-wrap:wrap;gap:12px;justify-content:center;font-size:13px;color:var(--cl-muted)}
.publish-layout{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(0,1fr);gap:24px;align-items:start;max-width:1000px;margin:0 auto}.publish-layout .cl-divider{margin:0}.photo-panel{display:flex;flex-direction:column;gap:16px}.upload-zone{background:var(--cl-surface-soft);color:var(--cl-muted);border:1px dashed var(--cl-border);border-radius:14px;min-height:200px;display:flex;flex-direction:column;gap:12px;align-items:center;justify-content:center;width:100%;font-size:13px}.upload-plus{font-size:32px;color:var(--cl-primary)}.photo-preview{position:relative}.photo-preview image{width:100%;height:210px;border-radius:12px}.photo-remove{position:absolute;right:8px;top:8px}.upload-actions,.inline-error{display:flex;align-items:center;justify-content:space-between;gap:12px}.upload-actions>text,.inline-error>text{flex:1}.publish-tip{display:block;font-size:13px;line-height:2.1;color:var(--cl-muted);margin-top:12px}@media(max-width:750px){.publish-layout{grid-template-columns:1fr}.publish-layout>.cl-stack{grid-row:1}.publish-layout>.cl-stack>.cl-notice,.publish-layout>.cl-stack>.cl-panel:last-child{display:none}.upload-zone{min-height:150px}}
</style>
