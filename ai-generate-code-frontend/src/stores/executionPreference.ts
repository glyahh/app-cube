import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import request from '@/request'
import { UserLoginStore } from './UserLogin'

/** 账号偏好按字段独立保存，避免覆盖其他开关。 */
export const useExecutionPreference = defineStore('executionPreference', () => {
  const login = UserLoginStore()
  const expanded = ref(false)
  const alertOnStartupFailure = ref(true)
  const alertOnRuntimeFailure = ref(false)
  const autoModelProxyFallback = ref(false)
  const autoNameProject = ref(true)
  const saving = ref(false)
  const ready = ref(false)
  let version = 0

  watch(
    () => login.userLogin.id,
    async (id) => {
      const current = ++version
      expanded.value = false
      alertOnStartupFailure.value = true
      alertOnRuntimeFailure.value = false
      autoModelProxyFallback.value = false
      autoNameProject.value = true
      saving.value = false
      ready.value = !id
      if (!id) return
      try {
        const { data } = await request.get('/user/preferences')
        if (current === version && (data.code === 0 || data.code === 20000)) {
          expanded.value = data.data.expandExecutionProcess === true
          // 后端已把库里的 null 折叠成默认值，这里只做兜底
          alertOnStartupFailure.value = data.data.alertOnStartupFailure !== false
          alertOnRuntimeFailure.value = data.data.alertOnRuntimeFailure === true
          autoModelProxyFallback.value = data.data.autoModelProxyFallback === true
          autoNameProject.value = data.data.autoNameProject !== false
        }
      } catch {
        /* 读取失败保持默认值。 */
      } finally {
        if (current === version) ready.value = true
      }
    },
    { immediate: true },
  )

  async function save(value: boolean) {
    const current = ++version
    saving.value = true
    try {
      const { data } = await request.put('/user/preferences', { expandExecutionProcess: value })
      if (data.code !== 0 && data.code !== 20000) throw new Error('保存失败，请重试')
      if (current === version) expanded.value = data.data.expandExecutionProcess === true
    } finally {
      if (current === version) {
        saving.value = false
        ready.value = true
      }
    }
  }

  async function saveAlertOnStartupFailure(value: boolean) {
    const current = ++version
    saving.value = true
    try {
      const { data } = await request.put('/user/preferences', { alertOnStartupFailure: value })
      if (data.code !== 0 && data.code !== 20000) throw new Error('保存失败，请重试')
      if (current === version) alertOnStartupFailure.value = value
    } finally {
      if (current === version) {
        saving.value = false
        ready.value = true
      }
    }
  }

  async function saveAlertOnRuntimeFailure(value: boolean) {
    const current = ++version
    saving.value = true
    try {
      const { data } = await request.put('/user/preferences', { alertOnRuntimeFailure: value })
      if (data.code !== 0 && data.code !== 20000) throw new Error('保存失败，请重试')
      if (current === version) alertOnRuntimeFailure.value = value
    } finally {
      if (current === version) {
        saving.value = false
        ready.value = true
      }
    }
  }

  async function saveAutoModelProxyFallback(value: boolean) {
    const current = ++version
    saving.value = true
    try {
      const { data } = await request.put('/user/preferences', { autoModelProxyFallback: value })
      if (data.code !== 0 && data.code !== 20000) throw new Error('保存失败，请重试')
      if (current === version) autoModelProxyFallback.value = value
    } finally {
      if (current === version) {
        saving.value = false
        ready.value = true
      }
    }
  }

  async function saveAutoNameProject(value: boolean) {
    const current = ++version
    saving.value = true
    try {
      const { data } = await request.put('/user/preferences', { autoNameProject: value })
      if (data.code !== 0 && data.code !== 20000) throw new Error('保存失败')
      if (current === version) autoNameProject.value = value
    } finally {
      if (current === version) {
        saving.value = false
        ready.value = true
      }
    }
  }

  return {
    expanded,
    alertOnStartupFailure,
    alertOnRuntimeFailure,
    autoModelProxyFallback,
    autoNameProject,
    saveAutoNameProject,
    saving,
    ready,
    save,
    saveAlertOnStartupFailure,
    saveAlertOnRuntimeFailure,
    saveAutoModelProxyFallback,
  }
})
