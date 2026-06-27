import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/upload',
    },
    {
      path: '/upload',
      name: 'upload',
      component: () => import('@/pages/UploadPage.vue'),
    },
    {
      path: '/stream',
      name: 'stream',
      component: () => import('@/pages/StreamPage.vue'),
    },
  ],
})

export default router
