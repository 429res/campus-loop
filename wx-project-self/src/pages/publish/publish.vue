<script setup>
import LoopPicker from '../../components/LoopPicker.vue'
import LoopButton from '../../components/LoopButton.vue'
import { ref, watch } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import http, { TOKEN_KEY, USER_KEY, imageUrl, isAbortError } from '../../common/http'
const categories = ref([]), loggedIn = ref(false), verifyingSession = ref(false), busy = ref(false), uploading = ref(false), error = ref(''), categoriesError = ref('')
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
  form.value = empty(); pendingFile.value = ''; uploadError.value = ''; draftNotice.value = ''; removedUpload.value = false
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
      ? '已恢复你的发布草稿；提交时服务端会重新核实图片归属，不会自动发布。'
      : '已恢复你的发布草稿，不会自动发布。'
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
  uploading.value = true; uploadError.value = ''; removedUpload.value = false
  try {
    uploadRequest = http.upload(filePath,{silent:true})
    const result = await uploadRequest
    if (attempt !== uploadAttempt || !isCurrentAccount(scope)) return
    form.value.imageUrl = result.url; pendingFile.value = ''
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
    uni.removeStorageSync(draftKey(scope.userId)); form.value = empty()
    uni.navigateTo({url:`/pages/detail/detail?id=${data.id}`})
  } catch(e) { requestFailed(e, scope, error) } finally { if (ownsAccountState(scope)) busy.value = false }
}
</script>
<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">让闲置，开始下一段旅程</text><text class="cl-subtitle">说说你有什么，也告诉我们你想要什么。</text></view>
    <view v-if="verifyingSession" class="cl-panel cl-empty" role="status"><text>正在验证当前账号…</text></view>
    <view v-else-if="!loggedIn" class="cl-panel cl-empty"><text class="cl-empty-symbol">↗</text><text>{{ error || sessionError || '登录后发布你的闲置' }}</text><text class="cl-hint">表单不会自动提交；重新登录后可恢复当前账号的非敏感草稿。</text><LoopButton v-if="sessionError" class="cl-btn" @click="verifySession">重试验证</LoopButton><LoopButton class="cl-btn cl-btn--primary" @click="login">重新登录</LoopButton></view>
    <view v-else class="publish-layout">
      <form class="cl-panel cl-form" @submit="publish">
        <view v-if="draftNotice" class="cl-notice" role="status">{{ draftNotice }}</view>
        <view v-if="categoriesError" class="inline-error" role="alert"><text class="cl-error">分类加载失败：{{ categoriesError }}</text><LoopButton class="cl-btn" @click="loadCategories">重试分类</LoopButton></view>
        <view class="cl-field"><text class="cl-field-title">物品标题 *</text><input v-model="form.title" class="cl-input" aria-label="物品标题" placeholder="例如：陪我度过大一的阅读台灯" maxlength="100" :disabled="busy" /></view>
        <view class="cl-grid-2"><view class="cl-field"><text class="cl-field-title">物品分类 *</text><LoopPicker :range="categories" range-key="name" :value="categories.findIndex(c=>c.id===form.categoryId)" aria-label="物品分类" :disabled="busy || !categories.length" @change="selectCategory($event,'categoryId')"><view class="cl-picker" aria-label="物品分类"><text>{{ categoryName(form.categoryId) }}</text><text>⌄</text></view></LoopPicker></view><view class="cl-field"><text class="cl-field-title">物品成色</text><LoopPicker :range="conditionNames" :value="form.conditionLevel - 1" :disabled="busy" @change="form.conditionLevel=Number($event.detail.value)+1" aria-label="物品成色"><view class="cl-picker" aria-label="物品成色"><text>{{ conditionNames[form.conditionLevel-1] }}</text><text>⌄</text></view></LoopPicker></view></view>
        <view class="cl-field"><text class="cl-field-title">物品描述 *</text><textarea v-model="form.description" class="cl-textarea" aria-label="物品描述" placeholder="介绍使用情况、尺寸和已知瑕疵，真实描述更容易匹配。" maxlength="2000" :disabled="busy" /></view>
        <view class="cl-field"><text class="cl-field-title">物品标签</text><input v-model="form.tags" class="cl-input" aria-label="物品标签" placeholder="用逗号分隔，最多 8 个，每个 20 字" maxlength="200" :disabled="busy" /></view>
        <view class="cl-divider"/><view class="cl-field"><text class="cl-section-title">我想换到</text><text class="cl-hint">分类是必须满足的条件；标签让推荐排序更贴近你的需求。</text></view>
        <view class="cl-field"><text class="cl-field-title">想要的分类 *</text><LoopPicker :range="categories" range-key="name" :value="categories.findIndex(c=>c.id===form.wantedCategoryId)" aria-label="想要的分类" :disabled="busy || !categories.length" @change="selectCategory($event,'wantedCategoryId')"><view class="cl-picker" aria-label="想要的分类"><text>{{ categoryName(form.wantedCategoryId) }}</text><text>⌄</text></view></LoopPicker></view>
        <view class="cl-field"><text class="cl-field-title">偏好标签</text><input v-model="form.wantedTags" class="cl-input" aria-label="偏好标签" placeholder="用逗号分隔，最多 8 个，每个 20 字" maxlength="200" :disabled="busy" /></view>
        <text v-if="error" class="cl-error" role="alert">{{ error }}</text><LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :disabled="busy || uploading || !!categoriesError" :loading="busy">{{ busy ? '正在发布…' : uploading ? '请等待图片上传' : '发布物品' }}</LoopButton>
      </form>
      <view class="cl-stack"><view class="cl-panel photo-panel"><text class="cl-field-title">给物品拍张照片</text><view v-if="pendingFile || form.imageUrl" class="photo-preview"><image :src="pendingFile || imageUrl(form.imageUrl)" mode="aspectFill"/><LoopButton class="cl-icon-btn photo-remove" aria-label="移除照片引用" :disabled="busy" @click="removeImage">×</LoopButton></view><LoopButton v-else class="upload-zone" :disabled="busy" @click="pickImage"><text class="upload-plus">＋</text><text>选择一张照片</text><text class="cl-hint">JPG / PNG / GIF，最大 5 MB</text></LoopButton><view v-if="uploading || uploadError" class="upload-actions"><text :class="uploadError ? 'cl-error' : 'cl-hint'">{{ uploading ? '图片上传中…' : uploadError }}</text><LoopButton v-if="uploading" class="cl-btn" @click="cancelUpload">取消上传</LoopButton><LoopButton v-else-if="uploadError && pendingFile" class="cl-btn" @click="startUpload(pendingFile)">重试上传</LoopButton></view><text v-if="removedUpload" class="cl-hint" role="status">已从表单移除图片引用；服务端文件未在此处删除。</text><text class="cl-hint">上传成功后才会绑定到物品；草稿恢复后的图片会在提交时由服务端重新核实归属。</text></view><view class="cl-notice">发布内容将写入你自己的 Campus Loop 数据库。请勿包含联系方式、证件或他人的隐私信息。</view><view class="cl-panel"><text class="cl-field-title">交换的一点小默契</text><text class="publish-tip">如实说明成色与瑕疵<br/>在校园公共区域交接<br/>正式确认前，物品仍可被发现</text></view></view>
    </view>
  </LoopLayout>
</template>
<style scoped>
.publish-layout{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(0,1fr);gap:24px;align-items:start;max-width:1000px;margin:0 auto}.publish-layout .cl-divider{margin:0}.photo-panel{display:flex;flex-direction:column;gap:16px}.upload-zone{background:var(--cl-surface-soft);color:var(--cl-muted);border:1px dashed var(--cl-border);border-radius:14px;min-height:200px;display:flex;flex-direction:column;gap:12px;align-items:center;justify-content:center;width:100%;font-size:13px}.upload-plus{font-size:32px;color:var(--cl-primary)}.photo-preview{position:relative}.photo-preview image{width:100%;height:210px;border-radius:12px}.photo-remove{position:absolute;right:8px;top:8px}.upload-actions,.inline-error{display:flex;align-items:center;justify-content:space-between;gap:12px}.upload-actions>text,.inline-error>text{flex:1}.publish-tip{display:block;font-size:13px;line-height:2.1;color:var(--cl-muted);margin-top:12px}@media(max-width:750px){.publish-layout{grid-template-columns:1fr}.publish-layout>.cl-stack{grid-row:1}.publish-layout>.cl-stack>.cl-notice,.publish-layout>.cl-stack>.cl-panel:last-child{display:none}.upload-zone{min-height:150px}}
</style>
