<script setup lang="ts">
import { UserLoginStore } from '@/stores/UserLogin'
import { computed, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  Avatar as AAvatar,
  Button as AButton,
  Menu as AMenu,
  Space as ASpace,
  message,
} from 'ant-design-vue'
import { userLogoutUsingPost } from '@/api/userController'
import { RightOutlined, SettingOutlined, SkinOutlined, AppstoreOutlined } from '@ant-design/icons-vue'

const userLoginStore = UserLoginStore()

/** 基础菜单项 */
const baseMenuItems = [
  { key: 'home', path: '/', label: '首页' },
  { key: 'project-import', path: '/project/import', label: '导入项目' },
  { key: 'code-generate', path: '/code/generate', label: '代码生成' },
  { key: 'loop', path: '/loop', label: 'Loop' },
]

/** 管理员专属菜单项（登录为 admin 时显示） */
const adminMenuItems = [
  { key: 'manage-users', path: '/admin/users', label: '用户管理' },
  { key: 'manage-apps', path: '/admin/apps', label: '应用管理' },
  { key: 'manage-chats', path: '/admin/chats', label: '对话管理' },
  { key: 'manage-loops', path: '/admin/loops', label: 'Loop 管理' },
  { key: 'manage-apply', path: '/admin/apply', label: '用户请求' },
]

/** 根据用户角色动态计算完整菜单 */
const menuItems = computed(() => {
  const userRole = userLoginStore.userLogin?.userRole
  const isAdmin = userRole === 'admin'
  return isAdmin ? [...baseMenuItems, ...adminMenuItems] : baseMenuItems
})

// ref,computed都是响应式数据,需要加上value
const menuItemsForMenu = computed(() =>
  menuItems.value.map((m) => ({ key: m.key, label: m.label }))
)

const router = useRouter()
const route = useRoute()

/**
 * 根据当前路径计算选中的菜单项
 */
const selectedKeys = computed(() => {
  const path = route.path
  const item = menuItems.value.find((m) => m.path === path)
  return item ? [item.key] : []
})

/**
 * 菜单项点击事件
 */
function onMenuSelect({ key }: { key: string | number }) {
  const item = menuItems.value.find((m) => m.key === String(key))
  if (item) router.push(item.path)
}

const isLoginPage = computed(() => route.path.startsWith('/user/login'))

function handleAuthClick() {
  if (isLoginPage.value) {
    router.push('/main_index')
  } else {
    router.push({
      path: '/user/login',
      query: { redirect: route.fullPath },
    })
  }
}

// 用户菜单悬停状态
const showUserMenu = ref(false)
const isLoggingOut = ref(false)

// 个人设置
function handleSettings() {
  router.push('/user/settings')
  showUserMenu.value = false
}

// 外观设置
function handleAppearance() {
  router.push('/user/appearance')
  showUserMenu.value = false
}

// 查看历史
function handleHistory() {
  router.push('/user/chats')
}

// 我的 Loop
function handleMyLoop() {
  router.push('/user/loops')
}

// 退出登录
async function handleLogout() {
  if (isLoggingOut.value) return

  isLoggingOut.value = true
  try {
    const res = await userLogoutUsingPost({})

    if (res.data.code === 20000 || res.data.code === 0) {
      // 清除用户信息
      userLoginStore.resetLoginUser()
      message.success('退出登录')
      // 跳转到登录页
      router.push('/user/login')
    } else {
      message.error('退出登录失败')
    }
  } catch (error) {
    console.error('logout error', error)
    message.error('退出登录失败')
  } finally {
    isLoggingOut.value = false
    showUserMenu.value = false
  }
}
</script>

<template>
  <div class="global-header">
    <div class="header-left">
      <router-link to="/main_index" class="logo-wrap">
        <span class="site-title">应用魔方</span>
      </router-link>
      <a-menu mode="horizontal" :selected-keys="selectedKeys" :items="menuItemsForMenu" class="header-menu"
        @select="onMenuSelect" />
    </div>
    <div class="header-right">
      <div v-if="userLoginStore.userLogin.id" class="user-area-wrapper">
        <a-button type="link" class="history-btn" @click="handleHistory">查看历史</a-button>
        <div class="user-area" @mouseenter="showUserMenu = true" @mouseleave="showUserMenu = false">
          <a-space class="user-info">
            <a-avatar :src="userLoginStore.userLogin.userAvatar" />
            <span class="user-name">{{ userLoginStore.userLogin.userName ?? '默认id 114514' }}</span>
          </a-space>

          <!-- 用户菜单下拉框 -->
          <Transition name="menu-slide">
            <div v-if="showUserMenu" class="user-menu" @mouseenter="showUserMenu = true">
              <div class="menu-item settings-item" @click="handleSettings">
                <SettingOutlined class="menu-icon" />
                <span class="menu-text">个人设置</span>
              </div>
              <div class="menu-item settings-item" @click="handleAppearance">
                <SkinOutlined class="menu-icon" />
                <span class="menu-text">外观设置</span>
              </div>
              <div class="menu-item settings-item" @click="handleMyLoop">
                <AppstoreOutlined class="menu-icon" />
                <span class="menu-text">我的 Loop</span>
              </div>
              <div class="menu-item logout-item" @click="handleLogout" :class="{ 'loading': isLoggingOut }">
                <RightOutlined class="menu-icon" />
                <span class="menu-text">退出登录</span>
              </div>
            </div>
          </Transition>
        </div>
      </div>
      <div v-else>
        <a-button type="primary" @click="handleAuthClick">
          {{ isLoginPage ? '返回' : '登录/注册' }}
        </a-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.global-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
  padding: 0 24px;
  gap: 24px;
}

.header-left {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
}

.logo-wrap {
  display: flex;
  align-items: center;
  text-decoration: none;
  color: inherit;
  margin-right: 32px;
  flex-shrink: 0;
}

.site-title {
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
}

.header-menu {
  flex: 1;
  min-width: 0;
  border-bottom: none;
  line-height: 64px;
}

.header-right {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.history-btn {
  padding: 0 8px;
  height: 32px;
}

/* 用户区域样式 */
.user-area-wrapper {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-area {
  position: relative;
  cursor: pointer;
  user-select: none;
}

.user-info {
  display: flex;
  align-items: center;
  padding: 4px 12px;
  border-radius: 20px;
  transition: background-color 0.2s ease;
}

.user-area:hover .user-info {
  background-color: var(--bg-mute, rgba(0, 0, 0, 0.04));
}

.user-name {
  font-size: 14px;
  color: var(--text-base, rgba(0, 0, 0, 0.85));
  font-weight: 500;
}

/* 用户菜单下拉框 */
.user-menu {
  position: absolute;
  top: 100%;
  right: 0;
  min-width: 120px;
  background: var(--bg-card, #fff);
  border-radius: 6px;
  box-shadow:
    0 4px 12px rgba(0, 0, 0, 0.15),
    0 0 0 1px rgba(0, 0, 0, 0.06);
  overflow: hidden;
  z-index: 1000;
  padding: 0;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 12px;
  line-height: 34px;
  cursor: pointer;
  transition: background-color 0.2s ease;
  color: var(--text-base, rgba(0, 0, 0, 0.85));
  font-size: 14px;
}

.menu-item:hover {
  background-color: var(--bg-mute, rgba(0, 0, 0, 0.06));
}

.menu-item.settings-item .menu-icon {
  color: var(--text-secondary, rgba(0, 0, 0, 0.65));
}

.menu-item.logout-item {
  color: #ff4d4f;
}

.menu-item.logout-item:hover {
  background-color: rgba(255, 77, 79, 0.1);
}

.menu-item.loading {
  opacity: 0.6;
  cursor: not-allowed;
}

.menu-icon {
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: inherit;
}

.menu-text {
  flex: 1;
}

/* 菜单动画 - 从下方滑入 */
.menu-slide-enter-active {
  transition: all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.menu-slide-leave-active {
  transition: all 0.2s ease-in;
}

.menu-slide-enter-from {
  opacity: 0;
  transform: translateY(10px) scale(0.95);
}

.menu-slide-leave-to {
  opacity: 0;
  transform: translateY(5px) scale(0.98);
}

@media (max-width: 768px) {
  .global-header {
    padding: 0 16px;
    gap: 12px;
  }

  .logo-wrap {
    margin-right: 16px;
  }

  .site-title {
    font-size: 16px;
  }

  .user-menu {
    min-width: 110px;
    right: -8px;
  }

  .menu-item {
    height: 32px;
    padding: 0 10px;
    line-height: 32px;
    font-size: 13px;
  }
}
</style>
