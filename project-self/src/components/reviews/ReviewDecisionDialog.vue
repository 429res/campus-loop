<script setup>
import { nextTick, reactive, ref, watch } from "vue";
import { imageUrl } from "@/api/uploadApi";
import SysDialog from "@/components/SysDialog.vue";
import {
  normalizeReviewReason,
  reviewMutationError,
  validateReviewReason,
} from "@/features/reviews/reviewForm";

const props = defineProps({
  visible: { type: Boolean, default: false },
  item: { type: Object, default: null },
  decision: { type: String, default: "APPROVE" },
  submitRequest: { type: Function, default: null },
  fixture: { type: Boolean, default: false },
  refreshRequest: { type: Function, default: null },
});
const emit = defineEmits(["close", "saved", "conflict", "refreshed"]);
const formRef = ref();
const submitting = ref(false);
const conflicted = ref(false);
const refreshing = ref(false);
const requestError = ref("");
const form = reactive({ reason: "" });
const rules = {
  reason: [
    {
      validator: (_rule, value, callback) => {
        const message = validateReviewReason(value);
        message ? callback(new Error(message)) : callback();
      },
      trigger: "blur",
    },
  ],
};
const decisionLabel = () =>
  props.decision === "REJECT" ? "驳回物品" : "通过审核";

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) return;
    form.reason = "";
    conflicted.value = false;
    requestError.value = "";
    await nextTick();
    formRef.value?.clearValidate();
  },
);

function close() {
  if (!submitting.value && !refreshing.value) emit("close");
}

async function submit() {
  if (submitting.value || refreshing.value || conflicted.value || props.item?.status !== "PENDING_REVIEW" || !props.submitRequest) return;
  requestError.value = "";
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  if (!Number.isInteger(props.item?.version) || props.item.version < 0) {
    requestError.value = "当前记录缺少并发版本，请刷新后重试";
    return;
  }
  const payload = {
    decision: props.decision,
    reason: normalizeReviewReason(form.reason),
    version: props.item.version,
  };
  submitting.value = true;
  try {
    const result = await props.submitRequest(payload, props.item);
    emit("saved", { payload, result });
  } catch (error) {
    requestError.value = reviewMutationError(error);
    if ((error?.response?.status ?? error?.status) === 409) {
      conflicted.value = true;
      emit("conflict", props.item?.id);
    }
  } finally {
    submitting.value = false;
  }
}
async function refreshVersion() {
  if (refreshing.value || !props.refreshRequest) return;
  refreshing.value = true;
  try {
    const current = await props.refreshRequest(props.item.id);
    emit("refreshed", current);
    conflicted.value = false;
    requestError.value = current.status === "PENDING_REVIEW" ? "已读取当前内容，请重新核对后再提交决定。" : "当前物品已不处于待审状态，请关闭此窗口。";
  } catch { requestError.value = "重新读取失败，理由已保留，请重试读取。"; }
  finally { refreshing.value = false; }
}
</script>

<template>
  <SysDialog
    :visible="visible"
    :title="decisionLabel()"
    :width="540"
    :height="420"
    :loading="submitting"
    :confirm-disabled="!submitRequest || conflicted || refreshing || item?.status !== 'PENDING_REVIEW'"
    @on-close="close"
    @on-confirm="submit"
  >
    <template #content>
      <el-alert
        v-if="fixture"
        title="审核组件夹具：只验证请求分支，不会改变物品状态。"
        type="warning"
        :closable="false"
        show-icon
      />
      <slot v-if="fixture" name="fixture-controls" />
      <dl class="review-target">
        <div><dt>物品</dt><dd>{{ item?.title || "—" }}</dd></div>
        <div><dt>当前状态</dt><dd>{{ ({PENDING_REVIEW:'待审核',AVAILABLE:'可交换',REJECTED:'已驳回',HIDDEN:'已下架',RESERVED:'交换中',EXCHANGED:'已交换'})[item?.status] || '—' }}</dd></div>

      </dl>
      <img v-if="item?.imageUrl" class="review-image" :src="imageUrl(item.imageUrl)" :alt="item.title" />
      <p class="review-content">{{ item?.description }}</p>
      <p>{{ item?.categoryName }} · 成色 {{ item?.conditionLevel }} · {{ (item?.tags || []).join('、') }}</p>
      <p>想换：{{ item?.wantedCategoryName }} · {{ (item?.wantedTags || []).join('、') || '不限标签' }}</p>
      <el-button v-if="conflicted && refreshRequest" :loading="refreshing" @click="refreshVersion">重新读取当前内容与版本</el-button>
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item label="审核理由" prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            resize="vertical"
            :disabled="submitting"
            placeholder="说明通过或驳回的依据"
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
.review-image {max-width:100%;max-height:260px;object-fit:contain;}
.review-content {white-space: pre-wrap; overflow-wrap: anywhere;}
.review-target {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  gap: 12px;
  padding: 14px;
  margin: 16px 0;
  border: 1px solid var(--cl-border);
  border-radius: 12px;
  background: var(--cl-surface-soft);
}
.review-target div {
  min-width: 0;
}
.review-target dt {
  color: var(--cl-muted);
  font-size: 11px;
}
.review-target dd {
  margin: 4px 0 0;
  overflow-wrap: anywhere;
  font-size: 13px;
  font-weight: 650;
}
@media (max-width: 520px) {
  .review-target {
    grid-template-columns: 1fr;
  }
}
</style>
