<template>
  <section class="workspace-grid compare-grid">
    <div class="panel">
      <div class="panel-header">
        <h2>多标的对比</h2>
        <span class="status-chip">暂未开放</span>
      </div>
      <p class="compare-boundary">
        当前执行链一次只支持一个股票或公司，尚不能保证多标的证据隔离和逐标的完成状态。
        请先在“研究”页逐个分析；独立的多标的执行器接入后再开放这里。
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
