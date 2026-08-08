<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { UserLoginStore } from '@/stores/UserLogin'
import { userUpdateUsingPut, userPersonalizationGetUsingGet, userPersonalizationPutUsingPut } from '@/api/userController'
import type { UserUpdateRequest } from '@/api/types'
import type { ApplyHistoryVO } from '@/api/types'
import { appApplyListMyHistoryUsingPost, appApplyUsingPost } from '@/api/appController'
import { message } from 'ant-design-vue'
import {
  LockOutlined,
  UserOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
  HistoryOutlined,
  StarOutlined,
} from '@ant-design/icons-vue'

type MenuKey = 'password' | 'profile' | 'apply' | 'history' | 'personalization'

const userLoginStore = UserLoginStore()
const activeMenu = ref<MenuKey>('profile')

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

// 菜单项
const menuItems = computed(() => {
  const items: { key: MenuKey; label: string; icon: any }[] = [
    { key: 'profile', label: '基本资料', icon: UserOutlined },
    { key: 'personalization', label: '个性化', icon: StarOutlined },
    { key: 'password', label: '安全设置', icon: LockOutlined },
    { key: 'apply', label: '申请管理', icon: TeamOutlined },
  ]
  // 仅普通用户可见：历史请求
  if (!isAdmin.value) {
    items.push({ key: 'history', label: '历史请求', icon: HistoryOutlined })
  }
  return items
})

// 动态菜单标签
const applyMenuLabel = computed(() => (isAdmin.value ? '用户请求' : '申请管理'))

// 修改密码
const passwordForm = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const passwordLoading = ref(false)

function validatePasswordForm(): string | null {
  if (!passwordForm.value.oldPassword) return '请输入原密码'
  if (!passwordForm.value.newPassword) return '请输入新密码'
  if (passwordForm.value.newPassword.length < 8) return '新密码不得少于 8 位'
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) return '两次输入的新密码不一致'
  return null
}

async function handlePasswordSubmit() {
  const err = validatePasswordForm()
  if (err) {
    message.warning(err)
    return
  }
  passwordLoading.value = true
  try {
    message.error('当前版本暂未接入修改密码接口，请联系管理员或稍后再试')
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '接口尚未实现，请稍后再试'
    message.error(msg)
  } finally {
    passwordLoading.value = false
  }
}

// 修改资料
const profileForm = ref<UserUpdateRequest>({
  id: undefined,
  userName: '',
  userAvatar: '',
  userProfile: '',
  userRole: '',
})
const profileLoading = ref(false)

onMounted(() => {
  if (userLoginStore.userLogin?.id) {
    profileForm.value = {
      id: userLoginStore.userLogin.id,
      userName: userLoginStore.userLogin.userName ?? '',
      userAvatar: userLoginStore.userLogin.userAvatar ?? '',
      userProfile: userLoginStore.userLogin.userProfile ?? '',
      userRole: userLoginStore.userLogin.userRole ?? '',
    }
  }
})

async function handleProfileSubmit() {
  if (!profileForm.value.id) {
    message.warning('用户信息异常')
    return
  }
  profileLoading.value = true
  try {
    // 使用 PUT /user/update 进行自助更新（不校验 admin 角色）
    const res = await userUpdateUsingPut({
      body: {
        id: profileForm.value.id,
        userName: profileForm.value.userName,
        userAvatar: profileForm.value.userAvatar,
        userProfile: profileForm.value.userProfile,
      },
    })
    if (res?.data === true) {
      message.success('资料更新成功')
      await userLoginStore.fetchLoginUser()
    } else {
      message.error('更新失败')
    }
  } catch (e: any) {
    message.error(e?.response?.data?.message || '更新失败')
  } finally {
    profileLoading.value = false
  }
}

const personalizationForm = ref({ appStyle: '', answerStyle: '' })
const personalizationLoading = ref(false)

async function loadPersonalization() {
  try {
    const res = await userPersonalizationGetUsingGet()
    if (res?.data?.data) {
      personalizationForm.value = {
        appStyle: res.data.data.appStyle ?? '',
        answerStyle: res.data.data.answerStyle ?? '',
      }
    }
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.message || '加载个性化配置失败')
  }
}

function customCount(val: string) {
  const remaining = 2000 - String(val ?? '').length
  return remaining > 0 ? '还可输入 ' + remaining + ' 字' : '已达上限'
}

async function handlePersonalizationSubmit() {
  personalizationLoading.value = true
  try {
    const res = await userPersonalizationPutUsingPut({ body: { appStyle: personalizationForm.value.appStyle, answerStyle: personalizationForm.value.answerStyle } })
    if (res?.data?.data === true) message.success('保存成功')
    else message.error(res?.data?.message || '保存失败')
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.message || '保存失败')
  }
  finally { personalizationLoading.value = false }
}

// 申请管理 / 用户请求（模拟数据）
const applyList = ref([
  { id: 1, userName: '用户A', userAccount: 'user_a', applyTime: '2025-02-14 10:00' },
  { id: 2, userName: '用户B', userAccount: 'user_b', applyTime: '2025-02-14 11:30' },
])
const applyLoading = ref(false)

const applyAdminReason = ref('')

function handleApprove(id: number) {
  applyLoading.value = true
  setTimeout(() => {
    applyList.value = applyList.value.filter((r) => r.id !== id)
    message.success('已同意申请')
    applyLoading.value = false
  }, 500)
}

function handleReject(id: number) {
  applyLoading.value = true
  setTimeout(() => {
    applyList.value = applyList.value.filter((r) => r.id !== id)
    message.info('已拒绝申请')
    applyLoading.value = false
  }, 500)
}

function handleApplyAdmin() {
  if (!userLoginStore.userLogin?.id) {
    message.warning('请先登录后再提交申请')
    return
  }
  applyLoading.value = true
  void appApplyUsingPost({
    body: {
      appId: 0,
      operate: 2,
      applyReason: applyAdminReason.value.trim() || '申请成为管理员',
    },
  })
    .then((res) => {
      if ((res.data.code === 0 || res.data.code === 20000) && res.data.data === true) {
        message.success('申请已提交，请等待管理员审核')
      } else {
        message.error(res.data.message || '申请提交失败')
      }
    })
    .catch((e: any) => {
      message.error(e?.response?.data?.message || '申请提交失败，请稍后再试')
    })
    .finally(() => {
      applyLoading.value = false
    })
}

// 历史请求（普通用户）
const historyLoading = ref(false)
const historyList = ref<ApplyHistoryVO[]>([])

function statusMeta(status?: number) {
  if (status === 1) return { text: '已通过', color: 'green' }
  if (status === 2) return { text: '已拒绝', color: 'red' }
  return { text: '待处理', color: 'orange' }
}

function operateText(operate?: number) {
  if (operate === 1) return '精选应用申请'
  if (operate === 2) return '管理员申请'
  return '未知类型'
}

const historyLastUpdatedAt = ref<string>('')

function formatNow() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function loadMyHistory(force = false) {
  if (isAdmin.value) return
  historyLoading.value = true
  try {
    const res = await appApplyListMyHistoryUsingPost({})
    if (res?.data?.code === 20000) {
      historyList.value = res.data.data ?? []
      historyLastUpdatedAt.value = formatNow()
    } else {
      message.error(res?.data?.message || '获取历史记录失败')
    }
  } catch (e: any) {
    message.error(e?.response?.data?.message || e?.message || '获取历史记录失败')
  } finally {
    historyLoading.value = false
  }
}

watch(
  () => activeMenu.value,
  (val) => {
    if (val === 'personalization') {
      void loadPersonalization()
    }
    if (val === 'history') {
      void loadMyHistory(true)
    }
  },
)
</script>

<template>
  <div class="settings-page">
    <aside class="settings-sidebar">
      <h2 class="sidebar-title">
        <SafetyCertificateOutlined class="title-icon" />
        个人设置
      </h2>
      <nav class="sidebar-nav">
        <button v-for="item in menuItems" :key="item.key" :class="['nav-item', { active: activeMenu === item.key }]"
          @click="activeMenu = item.key">
          <component :is="item.icon" class="nav-icon" />
          <span>{{ item.key === 'apply' ? applyMenuLabel : item.label }}</span>
        </button>
      </nav>
    </aside>

    <main class="settings-workbench">
      <Transition name="panel-fade" mode="out-in">
        <div :key="activeMenu" class="work-panel-wrap">
          <section v-if="activeMenu === 'profile'" class="work-panel">
            <h3 class="panel-title">基本资料</h3>
            <p class="panel-desc">修改昵称、头像和个性简介</p>
            <div class="form-card">
              <div class="avatar-row">
                <a-avatar :src="profileForm.userAvatar" :size="80" class="profile-avatar">
                  {{ (profileForm.userName || 'U')[0] }}
                </a-avatar>
                <div class="avatar-tip">头像将显示在个人主页</div>
              </div>
              <a-form layout="vertical" class="profile-form">
                <a-form-item label="昵称">
                  <a-input v-model:value="profileForm.userName" placeholder="请输入昵称" size="large" />
                </a-form-item>
                <a-form-item label="头像链接">
                  <a-input v-model:value="profileForm.userAvatar" placeholder="输入头像 URL" size="large" />
                </a-form-item>
                <a-form-item label="个人简介">
                  <a-textarea v-model:value="profileForm.userProfile" placeholder="写点什么介绍自己" :rows="4" size="large" />
                </a-form-item>
                <a-button type="primary" size="large" :loading="profileLoading" class="submit-btn"
                  @click="handleProfileSubmit">
                  保存修改
                </a-button>
              </a-form>
            </div>
          </section>

          <section v-else-if="activeMenu === 'personalization'" class="work-panel">
            <h3 class="panel-title">个性化</h3>
            <p class="panel-desc">配置你的代码生成偏好，每次生成时 AI 将参考以下指南</p>
            <div class="form-card">
              <a-form layout="vertical">
                <a-form-item label="应用风格">
                  <a-textarea v-model:value="personalizationForm.appStyle" placeholder="例如：我喜欢简洁、使用毛玻璃效果、主色调为蓝色..." :rows="5" size="large" :maxlength="2000" :show-count="customCount" />
                </a-form-item>
                <a-form-item label="回答风格">
                  <a-textarea v-model:value="personalizationForm.answerStyle" placeholder="例如：用正式的语气回答，代码与解释交替..." :rows="5" size="large" :maxlength="2000" :show-count="customCount" />
                </a-form-item>
                <a-button type="primary" size="large" :loading="personalizationLoading" class="submit-btn"
                  @click="handlePersonalizationSubmit">
                  保存配置
                </a-button>
              </a-form>
            </div>
          </section>
          <section v-else-if="activeMenu === 'password'" class="work-panel">
            <h3 class="panel-title">安全设置</h3>
            <p class="panel-desc">修改登录密码，请先验证原密码</p>
            <div class="form-card">
              <a-form layout="vertical" class="password-form">
                <a-form-item label="原密码">
                  <a-input-password v-model:value="passwordForm.oldPassword" placeholder="请输入原密码" size="large" />
                </a-form-item>
                <a-form-item label="新密码">
                  <a-input-password v-model:value="passwordForm.newPassword" placeholder="至少 8 位" size="large" />
                </a-form-item>
                <a-form-item label="确认新密码">
                  <a-input-password v-model:value="passwordForm.confirmPassword" placeholder="再次输入新密码" size="large" />
                </a-form-item>
                <a-button type="primary" size="large" :loading="passwordLoading" class="submit-btn"
                  @click="handlePasswordSubmit">
                  修改密码
                </a-button>
              </a-form>
            </div>
          </section>

          <section v-else-if="activeMenu === 'apply'" class="work-panel">
            <h3 class="panel-title">{{ isAdmin ? '用户请求' : '申请管理' }}</h3>
            <p class="panel-desc">
              {{ isAdmin ? '处理用户申请成为管理员的请求' : '申请成为管理员，等待审核' }}
            </p>

            <!-- 普通用户：申请入口 -->
            <div v-if="!isAdmin" class="form-card apply-card">
              <p class="apply-tip">若需管理员权限，可在此提交申请</p>
              <a-textarea
                v-model:value="applyAdminReason"
                :rows="3"
                placeholder="请简单说明你申请成为管理员的理由（选填）"
                class="apply-reason-input"
              />
              <a-button type="primary" size="large" class="apply-btn" :loading="applyLoading" @click="handleApplyAdmin">
                申请成为管理员
              </a-button>
            </div>

            <!-- 管理员：请求列表 -->
            <div v-else class="form-card">
              <div v-if="applyList.length === 0" class="empty-list">
                <TeamOutlined class="empty-icon" />
                <p>暂无待处理的申请</p>
              </div>
              <div v-else class="request-list">
                <div v-for="req in applyList" :key="req.id" class="request-item">
                  <div class="request-info">
                    <span class="req-name">{{ req.userName }}</span>
                    <span class="req-account">{{ req.userAccount }}</span>
                    <span class="req-time">{{ req.applyTime }}</span>
                  </div>
                  <div class="request-actions">
                    <a-button type="primary" size="small" :loading="applyLoading" @click="handleApprove(req.id)">
                      同意
                    </a-button>
                    <a-button size="small" :loading="applyLoading" @click="handleReject(req.id)">
                      拒绝
                    </a-button>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <section v-else class="work-panel">
            <h3 class="panel-title">历史请求</h3>
            <p class="panel-desc">查看自己提交过的申请记录、通过情况与管理员审核备注</p>

            <div class="form-card">
              <div class="history-toolbar">
                <div class="history-last-updated" v-if="historyLastUpdatedAt">上次刷新：{{ historyLastUpdatedAt }}</div>
                <a-button :loading="historyLoading" @click="loadMyHistory(true)">刷新</a-button>
              </div>

              <div v-if="historyList.length === 0 && !historyLoading" class="empty-list">
                <HistoryOutlined class="empty-icon" />
                <p>暂无历史申请记录</p>
              </div>

              <div v-else class="history-list">
                <div
                  v-for="item in historyList"
                  :key="String(item.applyId ?? `${item.operate ?? 'x'}-${item.createTime ?? ''}-${item.reviewTime ?? ''}`)"
                  class="history-item"
                >
                  <div class="history-head">
                    <div class="history-title">
                      <span class="history-operate">{{ operateText(item.operate) }}</span>
                      <a-tag :color="statusMeta(item.status).color">{{ statusMeta(item.status).text }}</a-tag>
                    </div>
                    <div class="history-time">
                      <span v-if="item.createTime">提交：{{ item.createTime }}</span>
                      <span v-if="item.reviewTime">审核：{{ item.reviewTime }}</span>
                    </div>
                  </div>

                  <div class="history-body">
                    <div class="history-row">
                      <span class="history-label">应用</span>
                      <span class="history-value">
                        <template v-if="item.appName">{{ item.appName }}</template>
                        <template v-else-if="item.appId">应用 #{{ item.appId }}</template>
                        <template v-else>—</template>
                      </span>
                    </div>
                    <div class="history-row">
                      <span class="history-label">申请理由</span>
                      <span class="history-value">{{ item.applyReason || '—' }}</span>
                    </div>
                    <div class="history-row">
                      <span class="history-label">审核备注</span>
                      <span class="history-value">
                        <template v-if="item.status === 2">{{ item.reviewRemark || '—' }}</template>
                        <template v-else-if="item.status === 1">通过</template>
                        <template v-else>待处理</template>
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </div>
      </Transition>
    </main>
  </div>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=DM+Sans:ital,opsz,wght@0,9..40,400;0,9..40,500;0,9..40,600;1,9..40,400&display=swap');

.settings-page {
  font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, sans-serif;
  display: flex;
  gap: 32px;
  max-width: 1000px;
  margin: 0 auto;
  min-height: 520px;
}

.settings-sidebar {
  flex-shrink: 0;
  width: 220px;
  background: var(--bg-card, #fff);
  border-radius: 12px;
  padding: 24px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  border: 1px solid var(--border-color, rgba(0, 0, 0, 0.06));
}

.sidebar-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-base, #1a1a1a);
  margin: 0 0 20px 0;
  letter-spacing: 0.02em;
}

.title-icon {
  font-size: 20px;
  color: #c17f59;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 12px 14px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary, #555);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
  text-align: left;
}

.nav-item:hover {
  background: rgba(193, 127, 89, 0.08);
  color: var(--text-base, #1a1a1a);
}

.nav-item.active {
  background: rgba(193, 127, 89, 0.14);
  color: #c17f59;
  font-weight: 500;
}

.nav-icon {
  font-size: 16px;
  opacity: 0.85;
}

.settings-workbench {
  flex: 1;
  min-width: 0;
}

.work-panel-wrap {
  display: block;
}

.work-panel {
  background: var(--bg-card, #fff);
  border-radius: 12px;
  padding: 28px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  border: 1px solid var(--border-color, rgba(0, 0, 0, 0.06));
}

.panel-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-base, #1a1a1a);
  margin: 0 0 6px 0;
}

.panel-desc {
  font-size: 13px;
  color: var(--text-secondary, #888);
  margin: 0 0 24px 0;
}

.form-card {
  margin-top: 8px;
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 24px;
}

.profile-avatar {
  flex-shrink: 0;
  background: linear-gradient(135deg, #c17f59 0%, #a86a45 100%);
  font-size: 28px;
  color: #fff;
}

.avatar-tip {
  font-size: 13px;
  color: var(--text-muted, #999);
}

.profile-form,
.password-form {
  max-width: 400px;
}

.submit-btn {
  margin-top: 8px;
  min-width: 140px;
  background: #c17f59;
  border-color: #c17f59;
}

.submit-btn:hover {
  background: #b06d48 !important;
  border-color: #b06d48 !important;
}

.apply-card {
  padding: 32px;
  text-align: left;
}

.apply-tip {
  font-size: 14px;
  color: var(--text-secondary, #666);
  margin-bottom: 20px;
}

.apply-reason-input {
  margin-bottom: 16px;
}

.apply-btn {
  background: #c17f59;
  border-color: #c17f59;
}

.apply-btn:hover {
  background: #b06d48 !important;
  border-color: #b06d48 !important;
}

.empty-list {
  padding: 48px;
  text-align: center;
  color: var(--text-muted, #999);
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
  opacity: 0.4;
}

.request-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.request-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  background: var(--bg-soft, #fafafa);
  border-radius: 8px;
  border: 1px solid var(--border-color, rgba(0, 0, 0, 0.06));
  transition: background 0.2s;
}

.request-item:hover {
  background: var(--bg-mute, #f5f5f5);
}

.request-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.req-name {
  font-weight: 500;
  color: var(--text-base, #1a1a1a);
}

.req-account {
  font-size: 13px;
  color: var(--text-secondary, #666);
}

.req-time {
  font-size: 12px;
  color: var(--text-muted, #999);
}

.request-actions {
  display: flex;
  gap: 8px;
}

.history-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.history-last-updated {
  font-size: 12px;
  color: var(--text-muted, #999);
  margin-right: auto;
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.history-item {
  padding: 16px 18px;
  border-radius: 10px;
  border: 1px solid var(--border-color, rgba(0, 0, 0, 0.06));
  background: var(--bg-soft, #fafafa);
}

.history-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.history-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.history-operate {
  font-weight: 600;
  color: var(--text-base, #1a1a1a);
}

.history-time {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted, #999);
}

.history-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.history-row {
  display: grid;
  grid-template-columns: 76px 1fr;
  gap: 10px;
  align-items: start;
}

.history-label {
  font-size: 13px;
  color: var(--text-secondary, #888);
}

.history-value {
  font-size: 13px;
  color: var(--text-base, #333);
  word-break: break-word;
}

/* 过渡动画 */
.panel-fade-enter-active,
.panel-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.panel-fade-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.panel-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 768px) {
  .settings-page {
    flex-direction: column;
    gap: 20px;
  }

  .settings-sidebar {
    width: 100%;
    display: flex;
    flex-direction: column;
    align-items: stretch;
  }

  .sidebar-nav {
    flex-direction: row;
    flex-wrap: wrap;
    gap: 8px;
  }

  .nav-item {
    flex: 1;
    min-width: 120px;
    justify-content: center;
  }
}
</style>
