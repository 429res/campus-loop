// UniApp transforms direct uni.method calls per platform. Passing the namespace itself
// loses those APIs in the optimized H5 build; keep the injectable adapters explicit.
export const platformStorage = {
  getStorageSync: key => uni.getStorageSync(key),
  setStorageSync: (key, value) => uni.setStorageSync(key, value),
}
export const platformNavigation = {
  switchTab: options => uni.switchTab(options),
  redirectTo: options => uni.redirectTo(options),
  navigateBack: options => uni.navigateBack(options),
  reLaunch: options => uni.reLaunch(options),
}
