<script setup>
import LoopPicker from '../../components/LoopPicker.vue'
import LoopButton from '../../components/LoopButton.vue'
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import http, { TOKEN_KEY, imageUrl } from '../../common/http'
const categories = ref([]), loggedIn = ref(false), busy = ref(false), uploading = ref(false), error = ref('')
const empty = () => ({title:'',description:'',categoryId:'',conditionLevel:3,tags:'',wantedCategoryId:'',wantedTags:'',imageUrl:''})
const form = ref(empty())
const conditionNames = ['有使用痕迹','正常使用','成色良好','几乎全新','全新未用']
onShow(async () => { loggedIn.value = !!uni.getStorageSync(TOKEN_KEY); try {categories.value = await http.get('/api/categories')} catch {} })
const login = () => uni.navigateTo({url:'/pages/login/login'})
const categoryName = id => categories.value.find(c => c.id === id)?.name || '请选择分类'
const selectCategory = (event,key) => { form.value[key] = categories.value[Number(event.detail.value)]?.id || '' }
async function pickImage() {
  if (uploading.value || busy.value) return
  uni.chooseImage({count:1,sizeType:['compressed'],sourceType:['album','camera'],async success(res) {
    uploading.value = true; error.value = ''
    try { const result = await http.upload(res.tempFilePaths[0]); form.value.imageUrl = result.url }
    catch(e) { error.value = e.message } finally { uploading.value = false }
  }})
}
async function publish() {
  if (busy.value || uploading.value) return
  error.value = ''
  if (!form.value.title.trim() || !form.value.description.trim() || !form.value.categoryId || !form.value.wantedCategoryId) { error.value = '请填写标题、物品描述、物品分类和想换的分类'; return }
  const tags = value => [...new Set(value.split(/[,，]/).map(t => t.trim()).filter(Boolean))]
  busy.value = true
  try {
    const data = await http.post('/api/items', {...form.value,title:form.value.title.trim(),description:form.value.description.trim(),tags:tags(form.value.tags),wantedTags:tags(form.value.wantedTags)},{silent:true})
    form.value = empty()
    uni.navigateTo({url:`/pages/detail/detail?id=${data.id}`})
  } catch(e) { error.value = e.message; if(e.status === 401) loggedIn.value = false } finally { busy.value = false }
}
</script>
<template>
  <LoopLayout><view class="cl-page-heading"><text class="cl-title">让闲置，开始下一段旅程</text><text class="cl-subtitle">说说你有什么，也告诉我们你想要什么。</text></view><view v-if="!loggedIn" class="cl-panel cl-empty"><text class="cl-empty-symbol">↗</text><text>登录后发布你的闲置</text><text class="cl-hint">每件物品都有明确的发布者，交换才更安心。</text><LoopButton class="cl-btn cl-btn--primary" @click="login">前往登录</LoopButton></view><view v-else class="publish-layout"><form class="cl-panel cl-form" @submit="publish"><view class="cl-field"><text class="cl-field-title">物品标题 *</text><input v-model="form.title" class="cl-input" aria-label="物品标题" placeholder="例如：陪我度过大一的阅读台灯" maxlength="100" :disabled="busy" /></view><view class="cl-grid-2"><view class="cl-field"><text class="cl-field-title">物品分类 *</text><LoopPicker :range="categories" range-key="name" :value="categories.findIndex(c=>c.id===form.categoryId)" aria-label="物品分类" :disabled="busy" @change="selectCategory($event,'categoryId')"><view class="cl-picker" aria-label="物品分类"><text>{{ categoryName(form.categoryId) }}</text><text>⌄</text></view></LoopPicker></view><view class="cl-field"><text class="cl-field-title">物品成色</text><LoopPicker :range="conditionNames" :value="form.conditionLevel - 1" :disabled="busy" @change="form.conditionLevel=Number($event.detail.value)+1" aria-label="物品成色"><view class="cl-picker" aria-label="物品成色"><text>{{ conditionNames[form.conditionLevel-1] }}</text><text>⌄</text></view></LoopPicker></view></view><view class="cl-field"><text class="cl-field-title">物品描述 *</text><textarea v-model="form.description" class="cl-textarea" aria-label="物品描述" placeholder="介绍使用情况、尺寸和已知瑕疵，真实描述更容易匹配。" maxlength="2000" :disabled="busy" /></view><view class="cl-field"><text class="cl-field-title">物品标签</text><input v-model="form.tags" class="cl-input" aria-label="物品标签" placeholder="用逗号分隔，例如：阅读,便携" maxlength="200" :disabled="busy" /></view><view class="cl-divider"/><view class="cl-field"><text class="cl-section-title">我想换到</text><text class="cl-hint">分类是必须满足的条件；标签让推荐排序更贴近你的需求。</text></view><view class="cl-field"><text class="cl-field-title">想要的分类 *</text><LoopPicker :range="categories" range-key="name" :value="categories.findIndex(c=>c.id===form.wantedCategoryId)" aria-label="想要的分类" :disabled="busy" @change="selectCategory($event,'wantedCategoryId')"><view class="cl-picker" aria-label="想要的分类"><text>{{ categoryName(form.wantedCategoryId) }}</text><text>⌄</text></view></LoopPicker></view><view class="cl-field"><text class="cl-field-title">偏好标签</text><input v-model="form.wantedTags" class="cl-input" aria-label="偏好标签" placeholder="用逗号分隔，例如：摄影,入门" maxlength="200" :disabled="busy" /></view><text v-if="error" class="cl-error" role="alert">{{ error }}</text><LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :disabled="busy || uploading" :loading="busy">{{ busy ? '正在发布…' : '发布物品' }}</LoopButton></form><view class="cl-stack"><view class="cl-panel photo-panel"><text class="cl-field-title">给物品拍张照片</text><view v-if="form.imageUrl" class="photo-preview"><image :src="imageUrl(form.imageUrl)" mode="aspectFill"/><LoopButton class="cl-icon-btn photo-remove" aria-label="移除已上传照片" :disabled="busy || uploading" @click="form.imageUrl=''">×</LoopButton></view><LoopButton v-else class="upload-zone" :disabled="uploading || busy" :loading="uploading" @click="pickImage"><text class="upload-plus">＋</text><text>{{ uploading ? '上传中…' : '选择一张照片' }}</text><text class="cl-hint">JPG / PNG / GIF，最大 5 MB</text></LoopButton><text class="cl-hint">未上传时显示原创占位插画。图片上传成功后才会绑定到发布记录。</text></view><view class="cl-notice">发布内容将写入你自己的 Campus Loop 数据库。请勿包含联系方式、证件或他人的隐私信息。</view><view class="cl-panel"><text class="cl-field-title">交换的一点小默契</text><text class="publish-tip">如实说明成色与瑕疵<br/>在校园公共区域交接<br/>正式确认前，物品仍可被发现</text></view></view></view></LoopLayout>
</template>
<style scoped>
.publish-layout{display:grid;grid-template-columns:minmax(0,1.7fr) minmax(0,1fr);gap:24px;align-items:start;max-width:1000px;margin:0 auto}.publish-layout .cl-divider{margin:0}.photo-panel{display:flex;flex-direction:column;gap:16px}.upload-zone{background:var(--cl-surface-soft);color:var(--cl-muted);border:1px dashed var(--cl-border);border-radius:14px;min-height:200px;display:flex;flex-direction:column;gap:12px;align-items:center;justify-content:center;width:100%;font-size:13px}.upload-plus{font-size:32px;color:var(--cl-primary)}.photo-preview{position:relative}.photo-preview image{width:100%;height:210px;border-radius:12px}.photo-remove{position:absolute;right:8px;top:8px}.publish-tip{display:block;font-size:13px;line-height:2.1;color:var(--cl-muted);margin-top:12px}@media(max-width:750px){.publish-layout{grid-template-columns:1fr}.publish-layout>.cl-stack{grid-row:1}.publish-layout>.cl-stack>.cl-notice,.publish-layout>.cl-stack>.cl-panel:last-child{display:none}.upload-zone{min-height:150px}}
</style>
