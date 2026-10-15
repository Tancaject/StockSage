<template>
  <section class="workspace-grid compare-grid">
    <div class="panel">
      <div class="panel-header">
        <h2>多标的对比</h2>
        <span class="status-chip">在对话中使用</span>
      </div>
      <p class="compare-boundary">
        多标的对比在对话中进行，例如“比较 AAPL 和 MSFT 最近四个季度的营收”。
        支持 2～4 个标的在行情、财报或新闻上的对比，每个标的的证据分开取证、分别验收；
        综合投资判断仍需逐个发起深度研究。该功能需服务端开启多标的对比开关。
      </p>
    </div>
  </section>
</template>

<style scoped>
.compare-boundary {
  color: var(--text-secondary);
  line-height: 1.7;
  margin: 16px 0 0;
  max-width: 720px;
}
</style>
