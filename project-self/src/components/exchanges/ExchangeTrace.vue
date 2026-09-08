<script setup>
import { computed } from "vue";
import { buildParticipantRows } from "@/features/disputes/disputePresentation";

const props = defineProps({ exchange: { type: Object, required: true } });
const participants = computed(() => buildParticipantRows(props.exchange));
const time = (value) => value?.replace("T", " ").replace("Z", " UTC") || "未声明";
</script>

<template>
  <section class="trace-section" aria-labelledby="participant-heading">
    <h3 id="participant-heading">参与者与物品流向</h3>
    <div class="participant-grid">
      <article v-for="person in participants" :key="person.userId" class="participant-card">
        <header><strong>{{ person.displayName }}</strong><el-tag size="small" :type="person.confirmationStatus === 'CONFIRMED' ? 'success' : 'warning'">{{ person.confirmationStatus === "CONFIRMED" ? "已确认邀请" : "待确认邀请" }}</el-tag></header>
        <p>交出物品 #{{ person.offeredItemId }} → 收到物品 #{{ person.receivedItemId }}</p>
        <dl>
          <div><dt>已交出</dt><dd>{{ time(person.handedOffAt) }}</dd></div>
          <div><dt>交出说明</dt><dd class="long-text">{{ person.handedOffNote || "未提供" }}</dd></div>
          <div><dt>已收到</dt><dd>{{ time(person.receivedAt) }}</dd></div>
          <div><dt>收到说明</dt><dd class="long-text">{{ person.receivedNote || "未提供" }}</dd></div>
        </dl>
      </article>
    </div>
    <div class="flow-list" aria-label="持久化物品流向">
      <div v-for="flow in exchange.flows || []" :key="flow.itemId" class="flow-row">
        <strong>物品 #{{ flow.itemId }}</strong><span>用户 #{{ flow.fromUserId }}</span><b aria-hidden="true">→</b><span>用户 #{{ flow.toUserId }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.trace-section{margin-top:24px}.participant-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.participant-card{padding:14px;border:1px solid var(--cl-border);border-radius:14px;background:var(--cl-surface-soft)}.participant-card header{display:flex;align-items:center;justify-content:space-between;gap:8px}.participant-card p{overflow-wrap:anywhere}.participant-card dl{display:grid;gap:8px;margin:0}.participant-card dl div{display:grid;grid-template-columns:72px 1fr;gap:8px}.participant-card dt{color:var(--cl-muted)}.participant-card dd{margin:0;overflow-wrap:anywhere}.long-text{white-space:pre-wrap}.flow-list{display:grid;gap:8px;margin-top:14px}.flow-row{display:grid;grid-template-columns:110px 1fr auto 1fr;align-items:center;gap:10px;padding:10px 12px;border-radius:12px;background:var(--cl-surface-soft)}@media(max-width:640px){.participant-grid{grid-template-columns:1fr}.flow-row{grid-template-columns:1fr 1fr}.flow-row strong{grid-column:1/-1}}
</style>
