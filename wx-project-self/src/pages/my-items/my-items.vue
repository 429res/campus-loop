<script setup>
import { ref } from 'vue'
import { onShow, onUnload, onPullDownRefresh } from '@dcloudio/uni-app'
import LoopInput from '../../components/LoopInput.vue'
import LoopSheet from '../../components/LoopSheet.vue'
import { showAppModal } from '../../common/modal'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopButton from '../../components/LoopButton.vue'
import LoopPicker from '../../components/LoopPicker.vue'
import http, { TOKEN_KEY, isAbortError } from '../../common/http'
import { itemStatusLabel } from '../../common/items'
const states = ['', 'PENDING_REVIEW', 'REJECTED', 'AVAILABLE', 'RESERVED', 'EXCHANGED', 'HIDDEN', 'DRAFT']
const labels = states.map(s => s ? itemStatusLabel(s) : '全部状态')
const rows = ref([]), total = ref(0), page = ref(1), selected = ref(0), busy = ref(false), error = ref('')
let request, sequence = 0, loadedToken = '', editorEpoch=0
const editorOpen=ref(false), editing=ref(null), form=ref({}), categories=ref([]), saving=ref(false), formError=ref(''), uncertain=ref(false)
const live=(token,epoch)=>token===uni.getStorageSync(TOKEN_KEY)&&epoch===editorEpoch
async function editItem(item){
 if(saving.value)return
 const token=uni.getStorageSync(TOKEN_KEY),epoch=++editorEpoch;formError.value='';uncertain.value=false
 try{const [value,catalog]=await Promise.all([http.get(`/api/items/mine/${item.id}`,{},{silent:true}),http.get('/api/categories',{}, {silent:true})]);if(!live(token,epoch))return
 editing.value=value;categories.value=catalog;form.value={title:value.title,description:value.description,categoryId:value.categoryId,wantedCategoryId:value.wantedCategoryId,conditionLevel:value.conditionLevel,tags:(value.tags||[]).join(','),wantedTags:(value.wantedTags||[]).join(','),imageUrl:value.imageUrl};editorOpen.value=true
 }catch(cause){if(live(token,epoch))error.value=cause.message}
}
function chooseCategory(field,event){form.value[field]=categories.value[Number(event.detail.value)]?.id}
const categoryName=id=>categories.value.find(value=>value.id===id)?.name||'请选择有效分类'
async function saveItem(){
 if(saving.value||uncertain.value||!editing.value)return
 const token=uni.getStorageSync(TOKEN_KEY),epoch=editorEpoch;saving.value=true;formError.value=''
 try{const tags=value=>value.split(/[,，]/).map(t=>t.trim()).filter(Boolean);const payload={...form.value,version:editing.value.version,tags:tags(form.value.tags),wantedTags:tags(form.value.wantedTags)}
 await http.put(`/api/items/${editing.value.id}`,payload,{silent:true,uncertainOnFailure:true});if(!live(token,epoch))return;editorOpen.value=false;await load()
 }catch(cause){if(live(token,epoch)){uncertain.value=!!cause.uncertain||cause.status===409;formError.value=cause.message;if(cause.status===400){try{const catalog=await http.get('/api/categories',{}, {silent:true});if(live(token,epoch))categories.value=catalog}catch{}}}}
 finally{if(live(token,epoch))saving.value=false}
}
async function withdraw(item){
 if(saving.value)return
 const token=uni.getStorageSync(TOKEN_KEY),epoch=editorEpoch;saving.value=true
 try{const choice=await showAppModal({title:'下架物品',content:'下架后不再参与推荐，当前不支持重新上架。确认继续？',danger:true});if(!choice.confirm||!live(token,epoch))return
 await http.post(`/api/items/${item.id}/withdraw`,{version:item.version},{silent:true,uncertainOnFailure:true});if(live(token,epoch))await load()
 }catch(cause){if(live(token,epoch)){await load();error.value=cause.message}}
 finally{if(live(token,epoch))saving.value=false}
}
function changeImage(){
 if(saving.value||uncertain.value)return
 const token=uni.getStorageSync(TOKEN_KEY),epoch=editorEpoch;saving.value=true
 uni.chooseImage({count:1,success:async result=>{try{if(!live(token,epoch))return;const value=await http.upload(result.tempFilePaths[0],{silent:true});if(live(token,epoch))form.value.imageUrl=value.url}catch(cause){if(live(token,epoch))formError.value=cause.message}finally{if(live(token,epoch))saving.value=false}},fail:()=>{if(live(token,epoch))saving.value=false}})
}

async function relist(item){
 if(saving.value)return
 const token=uni.getStorageSync(TOKEN_KEY);saving.value=true;error.value=''
 try{await http.post(`/api/items/${item.id}/relist`,{version:item.version},{silent:true,uncertainOnFailure:true});if(token===uni.getStorageSync(TOKEN_KEY)){await load();uni.showToast({title:'已提交审核',icon:'success'})}}
 catch(e){if(token===uni.getStorageSync(TOKEN_KEY)){error.value=e.message;await load()}}finally{saving.value=false}
}
async function load() {
  const current = ++sequence, token = uni.getStorageSync(TOKEN_KEY)
  request?.abort?.(); rows.value = []; total.value = 0; error.value = ''; busy.value = true
  if (loadedToken !== token) { page.value = 1; loadedToken = token; editorEpoch++;editorOpen.value=false;editing.value=null;saving.value=false;form.value={};categories.value=[] }
  try {
    request = http.get('/api/items/mine',{page:page.value,size:12,status:states[selected.value]}, {silent:true})
    const data = await request
    if (current !== sequence || token !== uni.getStorageSync(TOKEN_KEY)) return
    rows.value = data.records; total.value = data.total
  } catch (e) { if (current === sequence && !isAbortError(e)) error.value = e.message }
  finally { if (current === sequence) busy.value = false }
}
function select(event) { selected.value = Number(event.detail.value); page.value = 1; load() }
function turn(delta) { if (!busy.value) {page.value += delta; load()} }
const detail = item => uni.navigateTo({url:`/pages/detail/detail?id=${item.id}&mine=1`})
const login = () => uni.navigateTo({url:'/pages/login/login'})
onShow(load)
onPullDownRefresh(async () => {await load(); uni.stopPullDownRefresh()})
onUnload(() => {editorEpoch++;sequence++; request?.abort?.(); rows.value = []})
</script>
<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">我的物品</text><text class="cl-subtitle">管理闲置物品，查看审核与交换进度。</text></view>
    <view class="cl-panel cl-stack">
      <LoopPicker :range="labels" :value="selected" :disabled="busy" aria-label="我的物品状态" @change="select"><view class="cl-picker">{{ labels[selected] }} ⌄</view></LoopPicker>
      <text v-if="busy" class="cl-hint" role="status">正在读取…</text>
      <view v-else-if="error" class="cl-empty" role="alert"><text>{{ error }}</text><LoopButton class="cl-btn" @click="load">重试</LoopButton><LoopButton class="cl-btn" @click="login">登录</LoopButton></view>
      <template v-else>
        <view v-if="!rows.length" class="cl-empty">当前条件下没有物品</view>
        <view v-for="item in rows" :key="item.id" class="own-item">
          <view class="cl-row"><text class="cl-field-title">{{ item.title }}</text><text class="cl-tag">{{ itemStatusLabel(item.status) }}</text></view>
          <text class="cl-hint">{{ item.categoryName }} · {{ item.createdAt?.slice(0,10) }}</text>
          <text v-if="item.reviewDecision" class="review-reason">最近一次{{ item.reviewDecision === 'REJECT' ? '驳回' : '通过' }}（版本 {{ item.reviewedVersion }}）：{{ item.reviewReason }}</text>
          <view class="cl-row"><LoopButton class="cl-btn" @click="detail(item)">查看详情</LoopButton><LoopButton v-if="['AVAILABLE','PENDING_REVIEW','REJECTED','HIDDEN'].includes(item.status)" class="cl-btn" :disabled="saving" @click="editItem(item)">编辑</LoopButton><LoopButton v-if="item.status==='HIDDEN'" class="cl-btn cl-btn--primary" :disabled="saving" @click="relist(item)">重新上架</LoopButton><LoopButton v-if="item.status==='AVAILABLE'" class="cl-btn" :disabled="saving" @click="withdraw(item)">下架</LoopButton></view>
        </view>
      </template>
      <view class="cl-row"><LoopButton class="cl-btn" :disabled="busy || page <= 1" @click="turn(-1)">上一页</LoopButton><text>{{ page }} · 共 {{ total }} 件</text><LoopButton class="cl-btn" :disabled="busy || page * 12 >= total" @click="turn(1)">下一页</LoopButton></view>
    </view>
    <LoopSheet :model-value="editorOpen" title="编辑物品" @update:model-value="value=>{if(!saving)editorOpen=value}"><form v-if="editing" class="cl-form" @submit="saveItem">
      <text class="cl-hint">保存后重新进入审核，当前版本 {{editing.version}}。</text>
      <LoopInput v-model="form.title" class="cl-input" aria-label="物品标题" maxlength="100" :disabled="saving||uncertain"/>
      <textarea v-model="form.description" class="cl-textarea" aria-label="物品说明" maxlength="2000" :disabled="saving||uncertain"/>
      <text>物品分类</text><LoopPicker :range="categories.map(c=>c.name)" :value="categories.findIndex(c=>c.id===form.categoryId)" :disabled="saving||uncertain" @change="chooseCategory('categoryId',$event)"><view class="cl-input">{{categoryName(form.categoryId)}}</view></LoopPicker>
      <text>期望分类</text><LoopPicker :range="categories.map(c=>c.name)" :value="categories.findIndex(c=>c.id===form.wantedCategoryId)" :disabled="saving||uncertain" @change="chooseCategory('wantedCategoryId',$event)"><view class="cl-input">{{categoryName(form.wantedCategoryId)}}</view></LoopPicker>
      <text>成色</text><LoopPicker :range="['1 · 较旧','2 · 一般','3 · 良好','4 · 很新','5 · 全新']" :value="form.conditionLevel-1" :disabled="saving||uncertain" @change="form.conditionLevel=Number($event.detail.value)+1"><view class="cl-input">{{form.conditionLevel}} / 5</view></LoopPicker>
      <LoopInput v-model="form.tags" class="cl-input" aria-label="物品标签" placeholder="物品标签，以逗号分隔" :disabled="saving||uncertain"/>
      <LoopInput v-model="form.wantedTags" class="cl-input" aria-label="期望标签" placeholder="期望标签，以逗号分隔" :disabled="saving||uncertain"/>
      <LoopButton class="cl-btn" :disabled="saving||uncertain" @click="changeImage">更换图片</LoopButton>
      <text v-if="formError" class="cl-error">{{formError}}</text><LoopButton v-if="uncertain" class="cl-btn" :disabled="saving" @click="editItem(editing)">重新读取服务器内容</LoopButton>
      <LoopButton class="cl-btn cl-btn--primary" form-type="submit" :loading="saving" :disabled="saving||uncertain">保存并重新送审</LoopButton>
    </form></LoopSheet>
  </LoopLayout>
</template>
<style scoped>
.own-item{display:flex;flex-direction:column;gap:12px;padding:20px 0;border-bottom:1px solid var(--cl-border)}.own-item .cl-btn{align-self:flex-start}.review-reason{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.7}
</style>
