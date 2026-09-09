<script setup>
import {ref,computed,watch,onMounted,onBeforeUnmount} from 'vue'
import {onShow,onHide} from '@dcloudio/uni-app'
import LoopButton from './LoopButton.vue'
import LoopInput from './LoopInput.vue'
import LoopPicker from './LoopPicker.vue'
import http,{TOKEN_KEY,USER_KEY,imageUrl} from '../common/http'
import {showAppModal} from '../common/modal'
const props=defineProps({postId:{type:Number,default:0},active:{type:Boolean,default:true}})
const rows=ref([]),page=ref(1),total=ref(0),loading=ref(false),error=ref(''),notice=ref(''),mine=ref(false),keyword=ref('')
const user=ref(null),body=ref(''),image=ref(''),itemId=ref(null),items=ref([]),uploading=ref(false),progress=ref(0),saving=ref(false)
const replyBody=ref(''),replies=ref([]),replyPage=ref(1),replyTotal=ref(0),replyLoading=ref(false),actionBusy=ref(false),reporting=ref(false),reportReason=ref('')
const totalPages=computed(()=>Math.max(1,Math.ceil(total.value/20)))
const options=computed(()=>[{id:null,title:'不关联物品'},...items.value])
let sequence=0,replySequence=0,alive=true,identity='',postRequest=null,replyRequest=null
const key=()=>Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,14)
const time=value=>new Date(/Z$/.test(value)?value:value+'Z').toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})
const login=()=>uni.navigateTo({url:`/pages/login/login?redirect=${props.postId?'community':'matches'}${props.postId?'&id='+props.postId:''}`})
const open=id=>uni.navigateTo({url:'/pages/community/community?id='+id})
const openItem=id=>uni.navigateTo({url:'/pages/detail/detail?id='+id})
function account(){const token=uni.getStorageSync(TOKEN_KEY)||'';if(identity!==token){body.value='';image.value='';itemId.value=null;replyBody.value='';postRequest=null;replyRequest=null;items.value=[];mine.value=false;notice.value='';rows.value=[];replies.value=[];identity=token}user.value=token?uni.getStorageSync(USER_KEY):null;return token}
const current=token=>alive&&token===(uni.getStorageSync(TOKEN_KEY)||'')
async function load(){
 if(!props.active)return
 alive=true;const token=account(),attempt=++sequence;loading.value=true;error.value=''
 try{const data=props.postId?await http.get('/api/community/posts/'+props.postId,{}, {silent:true}):await http.get('/api/community/posts',{page:page.value,size:20,mine:mine.value,keyword:keyword.value.trim()},{silent:true});if(!current(token)||attempt!==sequence)return;rows.value=props.postId?[data]:data.records;total.value=props.postId?1:data.total;if(props.postId)await loadReplies()}
 catch(e){if(attempt===sequence&&current(token))error.value=e.message;else if(e.status===401&&attempt===sequence){account();error.value='请登录后继续'}}
 finally{if(attempt===sequence)loading.value=false}
 if(token&&!props.postId){try{const own=await http.get('/api/items/mine',{page:1,size:100},{silent:true});if(current(token)&&attempt===sequence)items.value=own.records.filter(i=>['AVAILABLE','RESERVED','EXCHANGED'].includes(i.status))}catch{}}
}
async function loadReplies(){const token=account(),attempt=++replySequence;replyLoading.value=true;try{const data=await http.get(`/api/community/posts/${props.postId}/replies`,{page:replyPage.value,size:20},{silent:true});if(current(token)&&attempt===replySequence){replies.value=data.records;replyTotal.value=data.total}}catch(e){if(current(token)&&attempt===replySequence)error.value=e.message}finally{if(attempt===replySequence)replyLoading.value=false}}
async function send(){
 const token=account();if(!token){login();return}if(saving.value||uploading.value||!body.value.trim())return
 const fields={body:body.value.trim(),itemId:itemId.value,imageUrl:image.value||null};if(!postRequest||JSON.stringify(postRequest.fields)!==JSON.stringify(fields))postRequest={fields,requestKey:key()}
 saving.value=true;error.value=''
 try{await http.post('/api/community/posts',{...postRequest.fields,requestKey:postRequest.requestKey},{silent:true});if(!current(token))return;body.value='';image.value='';itemId.value=null;postRequest=null;page.value=1;mine.value=false;notice.value='动态已发布';await load()}
 catch(e){if(current(token))error.value=e.message}finally{saving.value=false}
}
function pickImage(){const token=account();if(!token){login();return}if(uploading.value||saving.value)return;uni.chooseImage({count:1,sizeType:['compressed'],success:async result=>{if(!current(token))return;uploading.value=true;progress.value=0;error.value='';try{const data=await http.upload(result.tempFilePaths[0],{silent:true,onProgress:value=>{if(current(token))progress.value=value}});if(current(token)){image.value=data.url;notice.value='图片上传成功'}}catch(e){if(current(token))error.value=e.message}finally{uploading.value=false}}})}
async function react(post){const token=account();if(!token){login();return}if(actionBusy.value)return;actionBusy.value=true;error.value='';try{await http[post.liked?'delete':'put'](`/api/community/posts/${post.id}/like`,{}, {silent:true});if(current(token)){post.likes+=post.liked?-1:1;post.liked=!post.liked}}catch(e){if(current(token))error.value=e.message}finally{actionBusy.value=false}}
async function withdraw(post){const token=account();if(actionBusy.value)return;const choice=await showAppModal({title:'撤回动态',content:'撤回后，其他同学将无法查看这条动态。',confirmText:'撤回'});if(!choice.confirm||!current(token))return;actionBusy.value=true;try{await http.delete('/api/community/posts/'+post.id,{version:post.version},{silent:true});if(current(token)){notice.value='动态已撤回';if(props.postId){rows.value=[];replies.value=[]}else await load()}}catch(e){if(current(token))error.value=e.message}finally{actionBusy.value=false}}
async function reply(){const token=account();if(!token){login();return}if(saving.value||!replyBody.value.trim())return;const text=replyBody.value.trim();if(replyRequest?.body!==text)replyRequest={body:text,requestKey:key()};saving.value=true;error.value='';try{await http.post(`/api/community/posts/${props.postId}/replies`,replyRequest,{silent:true});if(current(token)){replyBody.value='';replyRequest=null;notice.value='回复已发送';replyPage.value=Math.max(1,Math.ceil((replyTotal.value+1)/20));await load()}}catch(e){if(current(token))error.value=e.message}finally{saving.value=false}}
async function removeReply(row){const token=account();if(actionBusy.value)return;const choice=await showAppModal({title:'撤回回复',content:'确定撤回这条回复吗？',confirmText:'撤回'});if(!choice.confirm||!current(token))return;actionBusy.value=true;try{await http.delete(`/api/community/posts/${props.postId}/replies/${row.id}`,{}, {silent:true});if(current(token))await load()}catch(e){if(current(token))error.value=e.message}finally{actionBusy.value=false}}
function startReport(){if(!account()){login();return}reporting.value=!reporting.value;reportReason.value=''}
async function report(){const token=account();if(!reportReason.value.trim()||actionBusy.value)return;actionBusy.value=true;try{await http.post(`/api/community/posts/${props.postId}/reports`,{reason:reportReason.value.trim()},{silent:true});if(current(token)){reporting.value=false;notice.value='举报已提交，管理员会查看处理'}}catch(e){if(current(token))error.value=e.message}finally{actionBusy.value=false}}
function filterMine(){if(!account()){login();return}mine.value=!mine.value;page.value=1;load()}
function changePage(delta){page.value+=delta;load()}
function changeReplies(delta){replyPage.value+=delta;loadReplies()}
onMounted(load);onShow(load);watch(()=>props.active,value=>{if(value)load();else sequence++});onHide(()=>{alive=false;sequence++;replySequence++});onBeforeUnmount(()=>{alive=false;sequence++;replySequence++})
</script>
<template>
 <view class="community-feed">
  <view v-if="!postId" class="cl-panel composer">
   <view class="community-heading"><text class="cl-section-title">校园动态</text><text class="cl-hint">晒晒闲置，说说需要，认识一起交换的同学。</text></view>
   <template v-if="user"><LoopInput v-model="body" multiline class="cl-textarea" aria-label="动态内容" placeholder="最近有什么想出手，或想找到的好物？" :maxlength="2000" :disabled="saving"/><image v-if="image" class="attached-image" :src="imageUrl(image)" mode="aspectFit"/><view class="composer-tools"><LoopButton class="cl-btn" :disabled="saving||uploading" @click="pickImage">{{uploading?`上传中 ${progress}%`:'添加图片'}}</LoopButton><LoopButton v-if="image" class="cl-btn" :disabled="saving||uploading" @click="image=''">移除图片</LoopButton><LoopPicker :range="options" range-key="title" :value="options.findIndex(i=>i.id===itemId)" :disabled="saving" aria-label="关联我的物品" @change="itemId=options[Number($event.detail.value)].id"><view class="cl-picker">{{options.find(i=>i.id===itemId)?.title||'关联我的物品'}} ⌄</view></LoopPicker><LoopButton class="cl-btn cl-btn--primary" :disabled="saving||uploading||!body.trim()" :loading="saving" @click="send">发布动态</LoopButton></view></template>
   <view v-else class="guest-prompt"><text>登录后可以发布动态、回复和点赞。</text><LoopButton class="cl-btn cl-btn--primary" @click="login">登录参与</LoopButton></view>
  </view>
  <view v-if="!postId" class="feed-toolbar"><LoopButton class="cl-btn" :class="{'cl-btn--primary':mine}" @click="filterMine">{{mine?'我的动态':'全部动态'}}</LoopButton><LoopInput v-model="keyword" class="cl-input" placeholder="搜索校园动态" aria-label="搜索校园动态" :maxlength="100" @confirm="page=1;load()"/><LoopButton class="cl-btn" :disabled="loading" @click="page=1;load()">搜索</LoopButton></view>
  <text v-if="notice" class="cl-notice feed-message" role="status">{{notice}}</text><view v-if="error" class="cl-notice feed-message" role="alert"><text class="cl-error">{{error}}</text><LoopButton class="cl-btn" @click="load">重新加载</LoopButton></view>
  <view v-if="loading" class="cl-empty" role="status">正在加载动态…</view>
  <view v-else-if="!rows.length&&!error" class="cl-panel cl-empty"><text>{{postId?'这条动态已撤回':'还没有动态，来分享第一件好物吧'}}</text></view>
  <view v-for="post in rows" :key="post.id" class="cl-panel feed-post">
   <view class="post-author"><image v-if="post.avatarUrl" class="post-avatar" :src="imageUrl(post.avatarUrl)" mode="aspectFill"/><text v-else class="cl-avatar">{{post.authorName?.slice(0,1)}}</text><view><text class="cl-field-title">{{post.authorName}}</text><text class="cl-hint">{{time(post.createdAt)}}{{post.status==='HIDDEN'?' · 已被隐藏':''}}</text></view><LoopButton v-if="post.authorId===user?.id" class="cl-btn cl-btn--quiet" :disabled="actionBusy" @click="withdraw(post)">撤回</LoopButton></view>
   <text class="post-body">{{post.body}}</text><image v-if="post.imageUrl" class="post-image" :src="imageUrl(post.imageUrl)" mode="aspectFit"/>
   <LoopButton v-if="post.itemId&&post.itemTitle" class="attached-item" @click="openItem(post.itemId)"><image :src="imageUrl(post.itemImage,post.itemTitle)" mode="aspectFill"/><view><text class="cl-hint">相关物品</text><text class="cl-field-title">{{post.itemTitle}}</text></view><text>查看 ›</text></LoopButton>
   <text v-else-if="post.itemId" class="cl-hint">关联物品暂不可见</text>
   <view class="post-actions"><LoopButton class="cl-btn" :class="{'cl-btn--primary':post.liked}" :aria-pressed="post.liked" :disabled="actionBusy||post.status!=='PUBLISHED'" @click="react(post)">{{post.liked?'♥ 已赞':'♡ 赞'}} {{post.likes}}</LoopButton><LoopButton v-if="!postId" class="cl-btn" @click="open(post.id)">回复 {{post.replies}}</LoopButton><LoopButton v-if="postId&&post.authorId!==user?.id" class="cl-btn cl-btn--quiet" @click="startReport">举报</LoopButton><LoopButton v-if="!postId" class="cl-btn cl-btn--quiet" @click="open(post.id)">查看讨论 ›</LoopButton></view>
  </view>
  <view v-if="postId&&rows.length" class="cl-panel replies">
   <text class="cl-section-title">回复 · {{replyTotal}}</text>
   <view v-if="reporting" class="report-box"><LoopInput v-model="reportReason" multiline class="cl-textarea" aria-label="举报原因" placeholder="请说明需要管理员查看的内容" :maxlength="500"/><LoopButton class="cl-btn cl-btn--primary" :disabled="actionBusy||!reportReason.trim()" @click="report">提交举报</LoopButton></view>
   <view v-if="rows[0].status==='PUBLISHED'" class="reply-composer"><LoopInput v-if="user" v-model="replyBody" multiline class="cl-textarea" aria-label="回复内容" placeholder="聊聊你的想法，或询问物品细节…" :maxlength="1000" :disabled="saving"/><LoopButton class="cl-btn cl-btn--primary" :disabled="saving||(!!user&&!replyBody.trim())" @click="reply">{{user?'发送回复':'登录后回复'}}</LoopButton></view>
   <view v-if="replyLoading" class="cl-empty">正在加载回复…</view><view v-else-if="!replies.length" class="cl-empty">还没有回复</view>
   <view v-for="row in replies" :key="row.id" class="reply-row"><view class="post-author"><text class="cl-avatar">{{row.authorName?.slice(0,1)}}</text><view><text class="cl-field-title">{{row.authorName}}</text><text class="cl-hint">{{time(row.createdAt)}}</text></view><LoopButton v-if="row.authorId===user?.id" class="cl-btn cl-btn--quiet" :disabled="actionBusy" @click="removeReply(row)">撤回</LoopButton></view><text class="post-body">{{row.body}}</text></view>
   <view v-if="replyTotal>20" class="feed-pagination"><LoopButton class="cl-btn" :disabled="replyLoading||replyPage<=1" @click="changeReplies(-1)">上一页</LoopButton><text>{{replyPage}}</text><LoopButton class="cl-btn" :disabled="replyLoading||replyPage*20>=replyTotal" @click="changeReplies(1)">下一页</LoopButton></view>
  </view>
  <view v-if="!postId&&total>20" class="feed-pagination"><LoopButton class="cl-btn" :disabled="loading||page<=1" @click="changePage(-1)">上一页</LoopButton><text>{{page}} / {{totalPages}}</text><LoopButton class="cl-btn" :disabled="loading||page>=totalPages" @click="changePage(1)">下一页</LoopButton></view>
 </view>
</template>
<style scoped>
.community-feed{max-width:800px;margin:0 auto;display:flex;flex-direction:column;gap:18px}.composer,.community-heading,.replies{display:flex;flex-direction:column;gap:16px}.composer .cl-textarea,.reply-composer .cl-textarea{min-height:110px;width:100%}.composer-tools,.feed-toolbar,.post-actions,.guest-prompt{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.composer-tools>.cl-btn--primary{margin-left:auto}.composer-tools .cl-picker{max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.feed-toolbar .cl-input{flex:1;min-width:140px}.feed-message{display:block;margin:0}.feed-post{display:flex;flex-direction:column;gap:18px}.post-author{display:flex;align-items:center;gap:12px}.post-author>view{flex:1;min-width:0}.post-author .cl-hint{display:block;margin-top:3px}.post-avatar{height:40px;width:40px;border-radius:50%}.post-body{display:block;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}.post-image,.attached-image{width:100%;max-width:100%;height:280px;background:var(--cl-surface-soft);border-radius:14px}.attached-image{height:150px}.attached-item{display:flex;align-items:center;gap:14px;padding:12px;background:var(--cl-surface-soft);border:1px solid var(--cl-border);border-radius:14px;text-align:left;color:var(--cl-text)}.attached-item image{height:62px;width:62px;border-radius:10px;flex-shrink:0}.attached-item>view{flex:1;min-width:0}.attached-item .cl-hint,.attached-item .cl-field-title{display:block}.post-actions{border-top:1px solid var(--cl-border);padding-top:14px}.post-actions>.cl-btn--quiet:last-child{margin-left:auto}.reply-composer,.report-box{display:flex;flex-direction:column;gap:12px}.reply-row{border-top:1px solid var(--cl-border);padding-top:18px}.reply-row .post-body{padding:12px 0 0 52px}.feed-pagination{display:flex;align-items:center;justify-content:center;gap:16px}@media(max-width:600px){.composer-tools>.cl-btn--primary{width:100%;margin-left:0}.composer-tools .cl-picker{max-width:190px}.feed-post,.composer,.replies{padding:20px}.post-image{height:220px}.feed-toolbar .cl-input{order:3;flex-basis:100%}.post-actions{gap:6px}.post-actions .cl-btn{font-size:12px;padding:8px 10px}.reply-row .post-body{padding-left:0}}
</style>
