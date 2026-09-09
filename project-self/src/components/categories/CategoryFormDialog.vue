<script setup>
import { nextTick, reactive, ref, watch } from "vue";
import SysDialog from "@/components/SysDialog.vue";
import {
  CATEGORY_NAME_MAX_LENGTH,
  categoryMutationError,
  normalizeCategoryName,
  validateCategoryName,
} from "@/features/categories/categoryForm";

const props = defineProps({
  visible: { type: Boolean, default: false },
  category: { type: Object, default: null },
  submitRequest: { type: Function, default: null },
  refreshRequest: { type: Function, default: null },
  fixture: { type: Boolean, default: false },
});
const emit = defineEmits(["close", "saved", "conflict", "refreshed"]);
const formRef = ref();
const submitting = ref(false);
const requestError = ref("");
const conflicted=ref(false);
const form = reactive({ name: "" });
const rules = {
  name: [
    {
      validator: (_rule, value, callback) => {
        const message = validateCategoryName(value);
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
    form.name = props.category?.name ?? "";
    conflicted.value=false;
    requestError.value = "";
    await nextTick();
    formRef.value?.clearValidate();
  },
);

function close() {
  if (!submitting.value) emit("close");
}

async function submit() {
  if (submitting.value || conflicted.value || !props.submitRequest) return;
  requestError.value = "";
  submitting.value = true;
  const valid = await formRef.value.validate().catch(() => false);
  if (!valid) {submitting.value=false;return;}
  const payload = { name: normalizeCategoryName(form.name) };
  try {
    const result = await props.submitRequest(payload, props.category);
    emit("saved", { payload, result });
  } catch (error) {
    requestError.value = categoryMutationError(error);
    if ((error?.response?.status ?? error?.status) === 409) {
      conflicted.value=!!props.category && !!props.refreshRequest;
      emit("conflict", props.category?.id);
    }
  } finally {
    submitting.value = false;
  }
}
async function refreshVersion(){
 if(submitting.value || !props.refreshRequest)return;
 submitting.value=true;
 try{const current=await props.refreshRequest(props.category.id);emit('refreshed',current);conflicted.value=false;requestError.value=`已读取当前分类「${current.name}」，版本 ${current.version}。你的输入已保留，请核对后提交。`}
 catch{requestError.value='分类重新读取失败，请关闭表单并刷新列表后重试。'}finally{submitting.value=false}
}
</script>

<template>
  <SysDialog
    :visible="visible"
    :title="category ? '编辑分类' : '新增分类'"
    :width="480"
    :height="160"
    :loading="submitting"
    :confirm-disabled="!submitRequest || conflicted"
    @on-close="close"
    @on-confirm="submit"
  >
    <template #content>
      <el-alert
        v-if="fixture"
        title="组件夹具：只验证交互和请求分支，不会写入数据库。"
        type="warning"
        :closable="false"
        show-icon
      />
      <slot v-if="fixture" name="fixture-controls" />
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="category-form"
        @submit.prevent="submit"
      >
        <el-form-item label="分类名称" prop="name">
          <el-input
            v-model="form.name"
            :maxlength="CATEGORY_NAME_MAX_LENGTH"
            show-word-limit
            autocomplete="off"
            :disabled="submitting"
            aria-describedby="category-name-help"
            placeholder="例如：图书教材"
          />
          <span id="category-name-help" class="field-help"
            >首尾空格会在提交前移除；服务端仍是最终校验。</span
          >
        </el-form-item>
        <el-button v-if="conflicted" :loading="submitting" @click="refreshVersion">重新读取当前分类版本</el-button>
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
.category-form {
  margin-top: 18px;
}
.field-help {
  margin-top: 6px;
  color: var(--cl-muted);
  font-size: 12px;
  line-height: 1.5;
}
</style>
