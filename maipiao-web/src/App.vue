<template>
  <router-view v-slot="{ Component }">
    <!--
      Films and schedules do not change while the user is browsing, so the
      list pages are kept alive. That also preserves scroll position when
      coming back from a detail page, which is the behaviour a native app has.
    -->
    <keep-alive :include="['HomeView']">
      <component :is="Component" />
    </keep-alive>
  </router-view>
</template>

<script setup>
// Root component. Keeps no state of its own - session state lives in the
// Pinia store so that it survives route changes.
</script>
