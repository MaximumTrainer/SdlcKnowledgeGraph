<template>
  <section class="not-found">
    <h1>Page not found</h1>
    <p>There is nothing at this address.</p>
    <router-link to="/">Back to the graph</router-link>
  </section>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  align-items: flex-start;
}
</style>
