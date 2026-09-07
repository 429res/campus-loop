import { onBeforeUnmount, watch } from "vue";

// A shared count avoids Element Plus instance-local delayed unlock races when
// one overlay closes while another opens. We own only our class, never remove
// another component's scroll lock or overwrite its inline overflow styles.
const locks = new Set();
export function acquireOverlayLock() {
  const key = Symbol("overlay");
  locks.add(key);
  document.documentElement.classList.add("cl-overlay-lock");
  return () => {
    locks.delete(key);
    if (!locks.size)
      document.documentElement.classList.remove("cl-overlay-lock");
  };
}
export function useOverlayLock(visible) {
  let release;
  watch(
    visible,
    (open) => {
      if (open && !release) release = acquireOverlayLock();
      if (!open && release) {
        release();
        release = undefined;
      }
    },
    { immediate: true, flush: "sync" },
  );
  onBeforeUnmount(() => release?.());
}
