<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    :width="`${width}px`"
    :before-close="onClose"
    :lock-scroll="false"
    append-to-body
    :close-on-click-modal="false"
    class="sys-dialog"
  >
    <div
      class="sys-dialog-body"
      :style="{ '--dialog-body-height': `${height}px` }"
    >
      <slot name="content" />
    </div>
    <template #footer>
      <div class="dialog-footer">
        <el-button plain @click="onClose">取消</el-button>
        <el-button
          type="primary"
          :loading="loading"
          :disabled="confirmDisabled"
          @click="onConfirm"
          >确定</el-button
        >
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { useOverlayLock } from "@/composables/useOverlayLock";
const props = defineProps({
  title: { type: String, default: "标题" },
  visible: { type: Boolean, default: false },
  width: { type: Number, default: 600 },
  height: { type: Number, default: 300 },
  loading: { type: Boolean, default: false },
  confirmDisabled: { type: Boolean, default: false },
});

useOverlayLock(() => props.visible);
const emit = defineEmits(["onClose", "onConfirm"]);
const onClose = () => emit("onClose");
const onConfirm = () => emit("onConfirm");
</script>

<style lang="scss">
.el-dialog.sys-dialog {
  /* Keep the control bands flush with the shell even when Element Plus loads later. */
  --el-dialog-padding-primary: 0px;
  max-width: calc(100vw - 28px);
  padding: 0;
  overflow: hidden;

  .el-dialog__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 62px;
    padding: 0 24px;
    margin: 0;
    background: transparent;
    border-bottom: 1px solid var(--line);
  }

  .el-dialog__title {
    position: relative;
    padding-left: 14px;
    color: var(--text-main);
    font-size: 16px;
    font-weight: 700;
  }

  .el-dialog__title::before {
    position: absolute;
    top: 2px;
    bottom: 2px;
    left: 0;
    width: 4px;
    background: var(--primary);
    border-radius: 99px;
    content: "";
  }

  .el-dialog__headerbtn {
    position: static;
    width: 34px;
    height: 34px;
  }

  .el-dialog__body {
    padding: 22px 24px;
  }

  .el-dialog__footer {
    padding: 15px 24px;
    background: transparent;
    border-top: 1px solid var(--line);
  }
}

.sys-dialog-body {
  min-height: min(var(--dialog-body-height), 48dvh);
  max-height: 60dvh;
  overflow-y: auto;
  padding: 3px;
}
</style>
