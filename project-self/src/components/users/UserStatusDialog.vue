<script setup>
import { computed, nextTick, reactive, ref, watch } from "vue";
import SysDialog from "@/components/SysDialog.vue";
import {
  USER_STATUS_REASON_MAX_LENGTH,
  buildUserStatusPayload,
  userStatusMutationError,
  validateUserStatusReason,
} from "@/features/users/userStatusForm";

const props = defineProps({
  visible: { type: Boolean, default: false },
  user: { type: Object, default: null },
  submitRequest: { type: Function, required: true },
});
const emit = defineEmits(["close", "saved", "conflict", "missing"]);
const formRef = ref();
const submitting = ref(false);
const requestError = ref("");
const form = reactive({ reason: "" });
const nextStatus = computed(() =>
  props.user?.status === "ACTIVE" ? "DISABLED" : "ACTIVE",
);
const actionLabel = computed(() =>
  nextStatus.value === "DISABLED" ? "停用账号" : "恢复启用",
);
const rules = {
  reason: [
    {
      validator: (_rule, value, callback) => {
        const message = validateUserStatusReason(value);
        message ? callback(new Error(message)) : callback();
      },
      trigger: "blur",
    },
  ],
};

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) return;
    form.reason = "";
    requestError.value = "";
    await nextTick();
    formRef.value?.clearValidate();
  },
);

function close() {
  if (!submitting.value) emit("close");
}

async function submit() {
  if (submitting.value || !props.user) return;
  requestError.value = "";
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  let payload;
  try {
    payload = buildUserStatusPayload(props.user, form.reason);
  } catch (error) {
    requestError.value = error.message;
    return;
  }
  submitting.value = true;
  try {
    const result = await props.submitRequest(payload, props.user);
    emit("saved", { payload, result });
  } catch (error) {
    requestError.value = userStatusMutationError(error);
    const status = error?.response?.status ?? error?.status;
    if (status === 409) emit("conflict", props.user.id);
    if (status === 404) emit("missing", props.user.id);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <SysDialog
    :visible="visible"
    :title="actionLabel"
    :width="520"
    :height="250"
    :loading="submitting"
    @on-close="close"
    @on-confirm="submit"
  >
    <template #content>
      <el-alert
        :title="
          nextStatus === 'DISABLED'
            ? '停用后该账号的全部会话立即撤销，且不能重新登录。已有物品不会被删除。'
            : '恢复启用不会恢复已撤销会话，用户仍需重新登录。'
        "
        type="warning"
        :closable="false"
        show-icon
      />
      <dl class="status-target">
        <div><dt>账号</dt><dd>{{ user?.username || "—" }}</dd></div>
        <div><dt>当前状态</dt><dd>{{ user?.status || "—" }}</dd></div>
        <div><dt>并发版本</dt><dd>{{ user?.version ?? "—" }}</dd></div>
      </dl>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="操作理由" prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="4"
            resize="vertical"
            :maxlength="USER_STATUS_REASON_MAX_LENGTH"
            show-word-limit
            :disabled="submitting"
            placeholder="请输入 1–500 字的启停依据"
          />
        </el-form-item>
        <el-alert
          v-if="requestError"
          :title="requestError"
          type="error"
          :closable="false"
          show-icon
        />
      </el-form>
    </template>
  </SysDialog>
</template>

<style scoped>
.status-target {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  gap: 12px;
  padding: 14px;
  margin: 16px 0;
  border: 1px solid var(--cl-border);
  border-radius: 12px;
  background: var(--cl-surface-soft);
}
.status-target div {
  min-width: 0;
}
.status-target dt {
  color: var(--cl-muted);
  font-size: 11px;
}
.status-target dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  font-size: 13px;
  font-weight: 650;
}
@media (max-width: 520px) {
  .status-target {
    grid-template-columns: 1fr;
  }
}
</style>
