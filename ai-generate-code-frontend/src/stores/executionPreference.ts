import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import request from '@/request'
import { UserLoginStore } from './UserLogin'

export const useExecutionPreference = defineStore('executionPreference', () => {
  const login = UserLoginStore()
  const expanded = ref(false)
  const saving = ref(false)
  const ready = ref(false)
  let version = 0

  watch(() => login.userLogin.id, async (id) => {
    const current = ++version
    expanded.value = false
    saving.value = false
    ready.value = !id
    if (!id) return
    try {
      const { data } = await request.get('/user/preferences')
      if (current === version && (data.code === 0 || data.code === 20000)) {
        expanded.value = data.data.expandExecutionProcess === true
      }
    } catch { /* 读取失败保持默认折叠。 */ }
    finally { if (current === version) ready.value = true }
  }, { immediate: true })

  async function save(value: boolean) {
    const current = ++version
    saving.value = true
    try {
      const { data } = await request.put('/user/preferences', { expandExecutionProcess: value })
      if (data.code !== 0 && data.code !== 20000) throw new Error('保存失败，请重试')
      if (current === version) expanded.value = data.data.expandExecutionProcess === true
    } finally {
      if (current === version) { saving.value = false; ready.value = true }
    }
  }
  return { expanded, saving, ready, save }
})
