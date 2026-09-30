<script setup lang="ts">
import { inject, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AUTH_SESSION } from '@/auth/session'

/**
 * Where the identity provider returns the browser after sign-in (#114): exchanges the code for tokens
 * and goes on to the page the user asked for in the first place.
 */
const session = inject(AUTH_SESSION, null)
const router = useRouter()
const failed = ref(false)

onMounted(async () => {
  if (!session) {
    await router.replace('/')
    return
  }
  try {
    await router.replace(await session.completeSignIn())
  } catch {
    failed.value = true
  }
})
</script>

<template>
  <section class="auth-callback">
    <p v-if="failed" role="alert">
      Signing in did not complete. <router-link to="/">Try again</router-link>.
    </p>
    <p v-else>Signing in…</p>
  </section>
</template>
