import http from './http'
const cache=new Map()
export function publicMember(id){
 const current=cache.get(id)
 if(current&&Date.now()-current.time<60000)return current.request
 const request=http.get(`/api/members/${id}`,{}, {silent:true}).catch(()=>null)
 cache.set(id,{time:Date.now(),request})
 if(cache.size>200)cache.delete(cache.keys().next().value)
 return request
}
