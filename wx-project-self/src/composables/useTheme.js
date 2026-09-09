import { ref, computed } from 'vue'
const theme = ref(uni.getStorageSync('campus-loop-theme') || 'light')
const reducedMotion = ref(!!uni.getStorageSync('campus-loop-reduced-motion'))
export const palettes=[{id:'rose',name:'樱花粉',color:'#ca376f',dark:'#ff8ab5',soft:'#fcebf2'},{id:'ocean',name:'晴空蓝',color:'#2167b5',dark:'#83bcff',soft:'#e8f2ff'},{id:'mint',name:'薄荷绿',color:'#147a68',dark:'#73d9be',soft:'#e4f5ee'},{id:'violet',name:'暮色紫',color:'#7952ba',dark:'#c8a7ff',soft:'#f0eaff'},{id:'amber',name:'暖阳橙',color:'#a85a18',dark:'#ffc484',soft:'#fff0df'}]
const palette=ref(uni.getStorageSync('campus-loop-palette')||'rose')
const style=ref(uni.getStorageSync('campus-loop-style')||'soft')
const appearanceStyle=computed(()=>{const p=palettes.find(p=>p.id===palette.value)||palettes[0];return {'--cl-primary':theme.value==='dark'?p.dark:p.color,'--cl-primary-soft':theme.value==='dark'?p.color+'30':p.soft,'--cl-radius':style.value==='simple'?'10px':'22px','--cl-shadow':style.value==='simple'?'none':'0 8px 28px #1b233109'}})
export const useTheme = () => {
  const apply = () => {
    const dark = theme.value === 'dark'
    // #ifdef H5
    document.documentElement.dataset.theme = theme.value
    Object.entries(appearanceStyle.value).forEach(([key,value])=>document.documentElement.style.setProperty(key,value))
    document.documentElement.dataset.style=style.value
    document.documentElement.dataset.motion = reducedMotion.value ? 'reduced' : 'system'
    document.documentElement.dataset.glass = navigator.hardwareConcurrency <= 4 ? 'reduced' : 'full'
    // #endif
    uni.setNavigationBarColor({ frontColor: dark ? '#ffffff' : '#000000', backgroundColor: dark ? '#1b2331' : '#ffffff' })
    const pages = getCurrentPages()
    const route = pages[pages.length-1]?.route
    if (['pages/home/home','pages/matches/matches','pages/publish/publish','pages/profile/profile'].includes(route)) {
      uni.setTabBarStyle({ backgroundColor: dark ? '#1b2331' : '#ffffff', color: dark ? '#b0bbcd' : '#596578', selectedColor: appearanceStyle.value['--cl-primary'] })
    }
  }
  const toggleTheme = () => { theme.value = theme.value === 'dark' ? 'light' : 'dark'; uni.setStorageSync('campus-loop-theme', theme.value); apply() }
  const toggleMotion = () => { reducedMotion.value = !reducedMotion.value; uni.setStorageSync('campus-loop-reduced-motion',reducedMotion.value); apply() }
  const setAppearance=(p,s)=>{if(palettes.some(x=>x.id===p))palette.value=p;if(['soft','simple'].includes(s))style.value=s;uni.setStorageSync('campus-loop-palette',palette.value);uni.setStorageSync('campus-loop-style',style.value);apply()}
  return { theme, apply, toggleTheme, reducedMotion, toggleMotion,palette,style,setAppearance,appearanceStyle }
}
