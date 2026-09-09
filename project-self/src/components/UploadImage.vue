<template>
  <div class="image-upload" :aria-busy="busy">
    <div class="image-grid">
      <div v-for="(url, index) in urls" :key="url" class="image-tile">
        <el-image
          :src="imageUrl(url)"
          fit="cover"
          :alt="`物品图片 ${index + 1}`"
          :preview-src-list="urls.map(imageUrl)"
          :initial-index="index"
          preview-teleported
        />
        <el-button
          class="remove-image"
          circle
          size="small"
          :icon="Close"
          :disabled="disabled || busy"
          :aria-label="`移除图片 ${index + 1}`"
          @click="remove(index)"
        />
      </div>
      <el-button
        v-if="urls.length < limit"
        class="upload-button"
        :loading="busy"
        :disabled="disabled || busy"
        :icon="Plus"
        @click="fileInput?.click()"
        >{{ busy ? "上传中" : "上传图片" }}</el-button
      >
    </div>
    <input
      ref="fileInput"
      class="file-input"
      type="file"
      accept="image/jpeg,image/png,image/gif"
      multiple
      aria-label="选择物品图片"
      :disabled="disabled || busy"
      @change="upload"
    />
    <p>
      最多 {{ limit }} 张，每张不超过 10 MB；支持
      JPG、PNG、GIF。首张图片用于列表展示。
    </p>
  </div>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from "vue";
import { Close, Plus } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { imageUrl, uploadImageApi } from "@/api/uploadApi.js";
const props = defineProps({
  modelValue: { type: String, default: "" },
  disabled: Boolean,
  limit: { type: Number, default: 6 },
});
const emit = defineEmits(["update:modelValue", "busy-change"]);
const fileInput = ref();
const busy = ref(false);
const urls = computed(() =>
  props.modelValue
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean),
);
let controller;
let disposed = false;
const remove = (index) =>
  emit("update:modelValue", urls.value.filter((_, i) => i !== index).join(","));
const upload = async (event) => {
  const files = Array.from(event.target.files || []);
  event.target.value = "";
  if (!files.length || busy.value || props.disabled) return;
  if (files.length + urls.value.length > props.limit)
    return ElMessage.warning(`最多上传 ${props.limit} 张图片`);
  if (
    files.some(
      (file) =>
        !["image/jpeg", "image/png", "image/gif"].includes(file.type) ||
        !/\.(jpe?g|png|gif)$/i.test(file.name),
    )
  )
    return ElMessage.warning("请选择 JPG、PNG、GIF 图片");
  if (files.some((file) => file.size === 0 || file.size > 10 * 1024 * 1024))
    return ElMessage.warning("图片不能为空，且每张不能超过 10 MB");
  busy.value = true;
  emit("busy-change", true);
  controller = new AbortController();
  try {
    for (const file of files) {
      const res = await uploadImageApi(file, controller.signal);
      if (disposed) return;
      if (
        res?.code !== 200 ||
        typeof res.data?.url !== "string" ||
        !imageUrl(res.data.url)
      ) {
        ElMessage.error(res?.msg || "图片上传失败，请重试");
        break;
      }
      emit(
        "update:modelValue",
        [...new Set([...urls.value, res.data.url])].join(","),
      );
      await nextTick();
    }
  } catch {
    // HTTP errors are reported by the existing interceptor; keep completed images.
  } finally {
    busy.value = false;
    if (!disposed) emit("busy-change", false);
  }
};
onBeforeUnmount(() => {
  disposed = true;
  controller?.abort();
});
</script>
<style scoped>
.image-upload {
  width: 100%;
}
.image-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.image-tile {
  position: relative;
  width: 116px;
  height: 82px;
}
.image-tile .el-image {
  width: 100%;
  height: 100%;
  border-radius: 10px;
  border: 1px solid var(--line);
}
.remove-image {
  position: absolute;
  top: -7px;
  right: -7px;
}
.upload-button {
  height: 82px;
  width: 116px;
  margin: 0;
  border-style: dashed;
}
.file-input {
  display: none;
}
p {
  margin: 10px 0 0;
  color: var(--text-soft);
  font-size: 12px;
  line-height: 1.7;
}
</style>
