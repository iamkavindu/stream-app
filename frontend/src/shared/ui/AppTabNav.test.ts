import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import AppTabNav from '@/shared/ui/AppTabNav.vue'

async function mountWithRouter(initialPath: string) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/upload', component: { template: '<div />' } },
      { path: '/stream', component: { template: '<div />' } },
    ],
  })

  await router.push(initialPath)
  await router.isReady()

  return mount(AppTabNav, {
    global: {
      plugins: [router],
    },
  })
}

describe('AppTabNav', () => {
  it('highlights Upload tab on /upload', async () => {
    const wrapper = await mountWithRouter('/upload')
    const uploadTab = wrapper.get('[data-testid="tab-upload"]')

    expect(uploadTab.classes()).toContain('bg-white')
  })

  it('links to Stream tab', async () => {
    const wrapper = await mountWithRouter('/upload')
    const streamTab = wrapper.get('[data-testid="tab-stream"]')

    expect(streamTab.attributes('href')).toBe('/stream')
  })

  it('highlights Stream tab on /stream', async () => {
    const wrapper = await mountWithRouter('/stream')
    const streamTab = wrapper.get('[data-testid="tab-stream"]')

    expect(streamTab.classes()).toContain('bg-white')
  })
})
