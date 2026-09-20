<template>
  <footer class="app-footer">
    <div class="mp-container">
      <div class="links">
        <span>关于麦票</span>
        <el-divider direction="vertical" />
        <span>联系方式</span>
        <el-divider direction="vertical" />
        <span>加入我们</span>
        <el-divider direction="vertical" />
        <span>意见反馈</span>
      </div>
      <p class="copyright">
        麦票 · 在线电影售票系统 · 个人作品集项目，非商业用途
      </p>
    </div>
  </footer>
</template>

<script setup>
// 静态页脚。之所以做成组件，是为了将来要加真实链接时只改一个文件，
// 而不是每个页面都改一遍。
</script>

<style scoped>
.app-footer {
  background: #fff;
  border-top: 1px solid var(--mp-border);
  padding: 28px 0;
  margin-top: auto;
}

.links {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--mp-text-muted);
  font-size: 13px;
}

.copyright {
  text-align: center;
  color: #bbb;
  font-size: 12px;
  margin: 12px 0 0;
}
</style>
