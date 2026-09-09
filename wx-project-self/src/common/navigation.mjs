const tabs = new Set(['home','matches','publish','profile'])
const pages = new Set([...tabs,'demands','favorites','exchanges','notifications','community','detail','my-items','governance','history'])
export function loginDestination(options={}) {
 const page=pages.has(options.redirect)?options.redirect:'profile'
 const candidate=options.id||options.exchangeId
 const id=/^[1-9][0-9]*$/.test(String(candidate||''))&&Number.isSafeInteger(Number(candidate))?String(candidate):''
 return {page,url:`/pages/${page}/${page}${id&&['exchanges','community','detail','history'].includes(page)?`?id=${id}`:''}`,tab:tabs.has(page)}
}
export function finishLogin(uni,options={}) {
 const target=loginDestination(options)
 if(target.tab)return uni.switchTab({url:target.url})
 return uni.redirectTo({url:target.url})
}
export function backWithinApp(uni,stack=[],fallback='home') {
 const page=tabs.has(fallback)?fallback:'home'
 const url=`/pages/${page}/${page}`
 const recover=()=>uni.switchTab({url,fail:()=>uni.reLaunch({url})})
 const previous=stack[stack.length-2]?.route
 if(stack.length>1&&previous&&!/pages\/(login|register)\//.test(previous))return uni.navigateBack({delta:1,fail:recover})
 return recover()
}
