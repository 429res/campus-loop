import { onBeforeUnmount, watch } from 'vue'

// H5 overlays share one reference-counted lock so a closing sheet cannot
// unlock the page while another sheet is already open. Native mini-program
// overlays keep platform scrolling behavior and compile out the DOM branch.
const locks = new Set()

export function acquireOverlayLock() {
  let release = () => {}
  // #ifdef H5
  const key = Symbol('overlay')
  locks.add(key)
  document.documentElement.classList.add('cl-overlay-lock')
  release = () => {
    locks.delete(key)
    if (!locks.size) document.documentElement.classList.remove('cl-overlay-lock')
  }
  // #endif
  return release
}

export function useOverlayLock(visible) {
  let release
  watch(visible, open => {
    if (open && !release) release = acquireOverlayLock()
    if (!open && release) {
      release()
      release = undefined
    }
  }, { immediate:true, flush:'sync' })
  onBeforeUnmount(() => release?.())
}
