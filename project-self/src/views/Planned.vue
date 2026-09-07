<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">THE NEXT CHAPTER</span>
      <h1>一起把循环补完整</h1>
      <p>下列业务尚未实现。以接口约定与状态设计为起点，四人协作推进。</p>
    </div>
    <span class="pill">待开发</span>
  </div>
  <div class="planned-grid">
    <section v-for="module in modules" :key="module.title" class="panel">
      <span class="eyebrow">{{ module.owner }}</span>
      <h2>{{ module.title }}</h2>
      <p>{{ module.description }}</p>
      <el-tag type="info" round>待开发 · 无写入接口</el-tag>
    </section>
  </div>
  <el-alert
    title="开发前请阅读根目录 docs/architecture.md、docs/api-contract.md 与 docs/roadmap.md；共享契约变更先在 Issue 或 PR 中协商。"
    type="info"
    :closable="false"
    show-icon
  />
</template>
<script setup>
const modules = [
  {
    title: "个人资料与权限管理",
    owner: "A · 基础服务",
    description: "补充用户资料、权限粒度与安全审计。保留真实认证边界。",
  },
  {
    title: "交换生命周期",
    owner: "B · 交换机制",
    description:
      "创建邀请、参与者确认、物品占用、交接、取消和超时。事务与并发校验先行。",
  },
  {
    title: "物品履历与维修记录",
    owner: "B · 溯源设计",
    description: "区分用户自述、双方确认与管理员核验，保存来源、操作者及时间。",
  },
  {
    title: "审核、举报与争议",
    owner: "C · 管理体验",
    description: "建立待审核队列、举报证据和处理记录，并对接真实管理接口。",
  },
  {
    title: "收藏与独立需求清单",
    owner: "D · 用户体验",
    description:
      "扩展我有什么、我想要什么、收藏和通知；与现有发布链路保持一致。",
  },
  {
    title: "业务统计与运行治理",
    owner: "A + C · 联合交付",
    description: "从真实数据构建趋势、分类统计和可审计运营视图。",
  },
];
</script>
