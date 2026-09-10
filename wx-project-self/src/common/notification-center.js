import {ref} from 'vue'
import http,{TOKEN_KEY,USER_KEY,onSessionCleared} from './http'
export const unreadCount=ref(0),notificationToast=ref(null)
let timer,owner=null,epoch=0,identity=''
export function dismissNotification(){notificationToast.value=null}
export function resetNotifications(){epoch++;identity='';unreadCount.value=0;notificationToast.value=null}
export function stopNotifications(key){if(owner!==key)return;owner=null;clearTimeout(timer);epoch++;notificationToast.value=null}
export function startNotifications(key){clearTimeout(timer);owner=key;const generation=++epoch;const poll=async()=>{
 if(owner!==key||generation!==epoch)return
 const token=uni.getStorageSync(TOKEN_KEY),user=uni.getStorageSync(USER_KEY)?.id
 const nextIdentity=token&&user?String(user)+':'+token:''
 if(nextIdentity!==identity){identity=nextIdentity;notificationToast.value=null;unreadCount.value=0}
 if(token&&user)try{
  const data=await http.get('/api/notifications',{page:1,size:5,unread:true},{silent:true})
  if(owner!==key||generation!==epoch||token!==uni.getStorageSync(TOKEN_KEY))return
  unreadCount.value=data.total
  const seenKey='campus-notifications-seen:'+user,seen=Number(uni.getStorageSync(seenKey))||0
  const fresh=data.records.filter(row=>row.id>seen)
  if(fresh.length){notificationToast.value={...fresh[0],count:data.total};uni.setStorageSync(seenKey,Math.max(seen,...fresh.map(row=>row.id)))}
 }catch{}
 if(owner===key&&generation===epoch)timer=setTimeout(poll,5000)
 };poll()}
onSessionCleared(resetNotifications)
export async function openNotification(row){
 const token=uni.getStorageSync(TOKEN_KEY)
 if(!token){notificationToast.value=null;return}
 notificationToast.value=null
 try{if(row?.id)await http.patch(`/api/notifications/${row.id}/read`,{}, {silent:true})}
 catch{if(token===uni.getStorageSync(TOKEN_KEY))uni.navigateTo({url:'/pages/notifications/notifications'});return}
 if(token!==uni.getStorageSync(TOKEN_KEY))return
 unreadCount.value=Math.max(0,unreadCount.value-1)
 if(row?.link&&/^\/pages\/(exchanges|my-items|governance|history|community)\/[a-z-]+(?:\?[a-zA-Z0-9=&_-]+)?$/.test(row.link))uni.navigateTo({url:row.link})
 else uni.navigateTo({url:'/pages/notifications/notifications'})
}
