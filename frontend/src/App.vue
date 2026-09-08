<template>
  <div id="app">
    <header class="app-header">
      <nav>
        <router-link to="/" class="brand">🕸 RepoDataGraph</router-link>
        <div class="nav-links">
          <router-link to="/nodes/Repository">Repositories</router-link>
          <router-link to="/nodes/Team">Teams</router-link>
          <router-link to="/nodes/Service">Services</router-link>
        </div>
      </nav>
    </header>
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<style>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  background: #f0f4f8;
  color: #1a202c;
}
.app-header {
  background: #1a202c;
  padding: 0 2rem;
}
.app-header nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
}
.brand {
  color: #63b3ed;
  text-decoration: none;
  font-size: 1.25rem;
  font-weight: 700;
}
.nav-links {
  display: flex;
  gap: 1.5rem;
}
.nav-links a {
  color: #a0aec0;
  text-decoration: none;
  font-size: 0.9rem;
}
.nav-links a:hover,
.nav-links a.router-link-active {
  color: #fff;
}
.app-main {
  max-width: 1100px;
  margin: 0 auto;
  padding: 2rem;
}
</style>
