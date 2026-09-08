import { ref } from 'vue'
const theme = ref(uni.getStorageSync('campus-loop-theme') || 'light')
const reducedMotion = ref(!!uni.getStorageSync('campus-loop-reduced-motion'))
export const useTheme = () => {
  const apply = () => {
    const dark = theme.value === 'dark'
    // #ifdef H5
    document.documentElement.dataset.theme = theme.value
    document.documentElement.dataset.motion = reducedMotion.value ? 'reduced' : 'system'
    document.documentElement.dataset.glass = navigator.hardwareConcurrency <= 4 ? 'reduced' : 'full'
    // #endif
    uni.setNavigationBarColor({ frontColor: dark ? '#ffffff' : '#000000', backgroundColor: dark ? '#1b2331' : '#ffffff' })
    const pages = getCurrentPages()
    const route = pages[pages.length-1]?.route
    if (['pages/home/home','pages/matches/matches','pages/publish/publish','pages/profile/profile'].includes(route)) {
      uni.setTabBarStyle({ backgroundColor: dark ? '#1b2331' : '#ffffff', color: dark ? '#b0bbcd' : '#596578', selectedColor: dark ? '#ff8ab5' : '#ca376f' })
    }
  }
  const toggleTheme = () => { theme.value = theme.value === 'dark' ? 'light' : 'dark'; uni.setStorageSync('campus-loop-theme', theme.value); apply() }
  const toggleMotion = () => { reducedMotion.value = !reducedMotion.value; uni.setStorageSync('campus-loop-reduced-motion',reducedMotion.value); apply() }
  return { theme, apply, toggleTheme, reducedMotion, toggleMotion }
}
