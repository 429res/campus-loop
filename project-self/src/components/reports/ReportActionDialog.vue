<script setup>
import { nextTick, reactive, ref, watch } from "vue";
import SysDialog from "@/components/SysDialog.vue";
import {
  buildReportPayload,
  REPORT_ACTIONS,
  REPORT_DECISIONS,
  reportMutationError,
  validateReportReason,
} from "@/features/reports/reportForm";

const props = defineProps({
  visible: { type: Boolean, default: false },
  report: { type: Object, default: null },
  action: { type: String, default: REPORT_ACTIONS.ACCEPT },
  submitRequest: { type: Function, default: null },
  refreshRequest: { type: Function, default: null },
  fixture: { type: Boolean, default: false },
});
const emit = defineEmits(["close", "saved", "conflict", "refreshed"]);
const formRef = ref();
const submitting = ref(false);
const refreshing = ref(false);
const conflicted = ref(false);
const requestError = ref("");
const form = reactive({ reason: "", decision: "UPHELD" });
const rules = {
  reason: [{ validator: (_rule, value, done) => {
    const message = validateReportReason(value);
    message ? done(new Error(message)) : done();
  }, trigger: "blur" }],
};
const isDecision = () => props.action === REPORT_ACTIONS.DECIDE;

watch(() => props.visible, async (visible) => {
  if (!visible) return;
  form.reason = "";
  form.decision = "UPHELD";
  conflicted.value = false;
  requestError.value = "";
  await nextTick();
  formRef.value?.clearValidate();
});

function close() {
  if (!submitting.value && !refreshing.value) emit("close");
}

async function submit() {
  if (submitting.value || refreshing.value || conflicted.value || !props.submitRequest) return;
  requestError.value = "";
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) return;
  if (!Number.isInteger(props.report?.version) || props.report.version < 0) {
    requestError.value = "当前举报缺少并发版本，请刷新后重试";
    return;
  }
  if (isDecision() && !REPORT_DECISIONS.includes(form.decision)) return;
  const payload = buildReportPayload({
    action: props.action,
    version: props.report.version,
    decision: form.decision,
    reason: form.reason,
  });
  submitting.value = true;
  try {
    const result = await props.submitRequest(payload, props.report, props.action);
    emit("saved", { payload, result });
  } catch (error) {
    requestError.value = reportMutationError(error);
    if ((error?.response?.status ?? error?.status) === 409) {
      conflicted.value = true;
      emit("conflict", props.report?.id);
    }
  } finally {
    submitting.value = false;
  }
}

async function refreshVersion() {
  if (refreshing.value || !props.refreshRequest) return;
  refreshing.value = true;
  try {
    const current = await props.refreshRequest(props.report.id);
    emit("refreshed", current);
    conflicted.value = false;
    requestError.value = "已读取服务端当前状态和版本，请重新核对后提交；理由仍保留。";
  } catch {
    requestError.value = "重新读取失败，理由已保留，请重试读取。";
  } finally {
    refreshing.value = false;
  }
}
</script>

<template>
  <SysDialog
    :visible="visible"
    :title="isDecision() ? '处理举报' : '受理举报'"
    :width="560"
    :height="430"
    :loading="submitting"
    :confirm-disabled="!submitRequest || conflicted || refreshing"
    @on-close="close"
    @on-confirm="submit"
  >
    <template #content>
      <el-alert v-if="fixture" title="举报组件夹具：只捕获请求，不写入数据库，也不改变队列状态。" type="warning" :closable="false" show-icon />
      <slot v-if="fixture" name="fixture-controls" />
      <dl class="report-target">
        <div><dt>举报</dt><dd>#{{ report?.id ?? "—" }}</dd></div>
        <div><dt>当前状态</dt><dd>{{ report?.status || "—" }}</dd></div>
        <div><dt>并发版本</dt><dd>{{ report?.version ?? "接口未提供" }}</dd></div>
      </dl>
      <p class="report-copy">{{ report?.reason }}</p>
      <el-button v-if="conflicted && refreshRequest" :loading="refreshing" @click="refreshVersion">重新读取当前状态与版本</el-button>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item v-if="isDecision()" label="处理决定">
          <el-radio-group v-model="form.decision" :disabled="submitting">
            <el-radio-button label="UPHELD">举报成立</el-radio-button>
            <el-radio-button label="DISMISSED">举报不成立</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="isDecision() ? '处理理由' : '受理说明'" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="5" maxlength="1000" show-word-limit resize="vertical" :disabled="submitting" placeholder="记录判断依据，不填写令牌、路径或其他敏感信息" />
        </el-form-item>
        <el-alert v-if="requestError" :title="requestError" type="error" :closable="false" show-icon />
      </el-form>
    </template>
  </SysDialog>
</template>

<style scoped>
.report-target {display:grid;grid-template-columns:repeat(3,1fr);gap:12px;padding:14px;margin:12px 0;border:1px solid var(--cl-border);border-radius:12px;background:var(--cl-surface-soft);}
.report-target dt {color:var(--cl-muted);font-size:11px;}
.report-target dd {margin:4px 0 0;font-weight:650;overflow-wrap:anywhere;}
.report-copy {white-space:pre-wrap;overflow-wrap:anywhere;}
@media (max-width:520px){.report-target{grid-template-columns:1fr;}}
</style>
