import { onMounted, onBeforeUnmount } from 'vue'
// Use the native KeyboardEvent in H5: UniApp's generic event adapter omits keyboard keys.
export const useKeyboard = (control, handler) => {
  // #ifdef H5
  let element
  onMounted(() => {element=control.value?.$el || control.value;element?.addEventListener('keydown',handler)})
  onBeforeUnmount(() => element?.removeEventListener('keydown',handler))
  // #endif
}
