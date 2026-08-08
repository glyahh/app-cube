<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userLoginUsingPost, userRegisterUsingPost } from '@/api/userController'
import { UserLoginStore } from '@/stores/UserLogin'

const isSignIn = ref(true)
const loading = ref(false)

type LoginTipType = 'success' | 'error'

const loginTip = ref<{ type: LoginTipType; text: string } | null>(null)
let loginTipTimer: number | undefined

const showLoginSuccessModal = ref(false)
let loginSuccessTimer: number | undefined

const loginForm = ref({
  account: '',
  password: '',
})

const registerForm = ref({
  account: '',
  password: '',
  confirmPassword: '',
})

const router = useRouter()
const userLoginStore = UserLoginStore()
const POST_LOGIN_PATH = '/main_index'


function toggle(mode: 'signin' | 'signup') {
  isSignIn.value = mode === 'signin'
}

async function handleLogin() {
  if (!loginForm.value.account || !loginForm.value.password) {
    message.warning('请输入账号和密码')
    return
  }

  if (loginForm.value.password.length < 8) {
    message.warning('密码不得小于8位')
    return
  }

  // 使用 loading 状态，防止用户重复点击
  loading.value = true
  try {
    // 前端已经正确调用 userLoginUsingPost，向后端发送账号和密码
    const res = await userLoginUsingPost({
      body: {
        userAccount: loginForm.value.account,
        userPassword: loginForm.value.password,
      },
    })

    if (res.data.code === 20000 && res.data.data) {
      userLoginStore.setLoginUser(res.data.data)

      // 展示登录成功弹窗（毛玻璃、半透明、2s 后自动关闭并跳转）
      showLoginSuccessModal.value = true
      if (loginSuccessTimer) {
        window.clearTimeout(loginSuccessTimer)
      }
      loginSuccessTimer = window.setTimeout(() => {
        showLoginSuccessModal.value = false
        loginSuccessTimer = undefined
        router.replace(POST_LOGIN_PATH)
      }, 2000)
    } else {
      // 后端返回了非 20000 状态：统一提示
      loginTip.value = { type: 'error', text: '密码或账号有误' }
    }
  } catch (error) {
    // 请求本身失败（网络/CORS/接口 500 等），也统一给出同样的提示
    console.error('login error', error)
    loginTip.value = { type: 'error', text: '密码或账号有误' }
  } finally {
    loading.value = false
  }
}

/**
 * 用户提交
 */
async function handleRegister() {

  if (!registerForm.value.account || !registerForm.value.password || !registerForm.value.confirmPassword) {
    message.warning('请完整填写注册信息')
    return
  }

  if (registerForm.value.password.length < 8) {
    message.warning('密码不得小于8位')
    return
  }

  if (registerForm.value.password !== registerForm.value.confirmPassword) {
    message.error('两次输入的密码不一致')
    return
  }

  //
  loading.value = true
  try {
    const res = await userRegisterUsingPost({
      body: {
        userAccount: registerForm.value.account,
        userPassword: registerForm.value.password,
        checkPassword: registerForm.value.confirmPassword,
      },
    })

    if (res.data.code === 20000) {
      message.success('注册成功，请登录')
      isSignIn.value = true
      loginForm.value.account = registerForm.value.account
      loginForm.value.password = ''
    } else {
      message.error(res.data.message || '注册失败')
    }
  } finally {
    loading.value = false
  }
}

// 已登录用户访问登录页时重定向到主页
onMounted(() => {
  // userLogin存在并且id为真值
  if (!userLoginStore.userLogin?.id) return
  router.replace(POST_LOGIN_PATH)
})
</script>

<template>
  <div class="login-page">
    <div class="bg-layer" />
    <div class="card" :class="{ 'signup-mode': !isSignIn }">
      <div class="panel panel-signin" :class="{ active: isSignIn }">
        <div class="panel-inner">
          <h2 class="title">Sign In</h2>
          <transition name="fade">
            <div v-if="loginTip" class="login-tip" :class="`login-tip--${loginTip.type}`">
              <span class="login-tip-dot" />
              <span class="login-tip-text">{{ loginTip.text }}</span>
            </div>
          </transition>
          <a-form layout="vertical" :model="loginForm" @finish="handleLogin">
            <a-form-item label="Account" name="account">
              <a-input v-model:value="loginForm.account" size="large" />
            </a-form-item>
            <a-form-item label="Password" name="password">
              <a-input-password v-model:value="loginForm.password" size="large" />
            </a-form-item>
            <a-typography-text type="secondary" class="forgot">
              Forgot your password?
            </a-typography-text>
            <a-form-item class="btn-wrap">
              <a-button type="primary" html-type="submit" size="large" shape="round" :loading="loading"
                class="primary-btn">
                SIGN IN
              </a-button>
            </a-form-item>
          </a-form>
        </div>
      </div>

      <div class="panel panel-signup" :class="{ active: !isSignIn }">
        <div class="panel-inner">
          <h2 class="title">Sign Up</h2>
          <a-form layout="vertical" :model="registerForm" @finish="handleRegister">
            <a-form-item label="Account" name="account">
              <a-input v-model:value="registerForm.account" size="large" />
            </a-form-item>
            <a-form-item label="Password" name="password">
              <a-input-password v-model:value="registerForm.password" size="large" />
            </a-form-item>
            <a-form-item label="Confirm Password" name="confirmPassword">
              <a-input-password v-model:value="registerForm.confirmPassword" size="large" />
            </a-form-item>
            <a-form-item class="btn-wrap">
              <a-button type="primary" html-type="submit" size="large" shape="round" :loading="loading"
                class="primary-btn">
                SIGN UP
              </a-button>
            </a-form-item>
          </a-form>
        </div>
      </div>

      <div class="slider">
        <div class="slider-bg" />
        <div class="slider-content" :class="{ 'to-right': !isSignIn }">
          <template v-if="isSignIn">
            <a-button shape="round" size="large" class="ghost-btn" @click="toggle('signup')">
              SIGN UP
            </a-button>
          </template>
          <template v-else>
            <a-button shape="round" size="large" class="ghost-btn" @click="toggle('signin')">
              SIGN IN
            </a-button>
          </template>
        </div>
      </div>
    </div>
  </div>

  <!-- 登录成功弹窗：半透明毛玻璃，2s 自动消失，无法手动关闭 -->
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="showLoginSuccessModal" class="login-success-overlay" @click.self.prevent>
        <div class="login-success-modal">
          <div class="login-success-icon">
            <span class="checkmark">✓</span>
          </div>
          <p class="login-success-text">用户登录成功</p>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.login-page {
  position: fixed;
  inset: 0;
  /* 预留 header 和 footer 的高度，背景填满其余区域 */
  padding-top: 64px;
  padding-bottom: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #000;
  z-index: 0;
}

.bg-layer {
  position: absolute;
  inset: 0;
  background-image: url('@/picture/login/image.png');
  background-size: cover;
  background-position: center;
  filter: brightness(0.9);
  transform: scale(1.05);
}

.card {
  position: relative;
  width: 960px;
  max-width: 100%;
  height: 540px;
  display: flex;
  border-radius: 18px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(6px);
}

.panel {
  position: relative;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-card, rgba(255, 255, 255, 0.9));
  transition:
    transform 0.6s ease,
    opacity 0.6s ease;
}

.panel-signin {
  transform: translateX(0);
  z-index: 2;
}

.panel-signup {
  transform: translateX(0);
  z-index: 1;
}

/* 左右两边滑动切换效果 */
.card {
  position: relative;
}

.panel-signin,
.panel-signup {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 50%;
}

.panel-signin {
  left: 0;
}

.panel-signup {
  right: 0;
}

/* 默认：显示左侧 Sign In，右侧 Sign Up 隐藏（对应登录态） */
.card .panel-signin {
  opacity: 1;
  pointer-events: auto;
  transform: translateX(0);
}

.card .panel-signup {
  opacity: 0;
  pointer-events: none;
  transform: translateX(20px);
}

/* signup-mode 时（即 isSignIn === false）：显示右侧 Sign Up，左侧 Sign In 收起（对应注册态） */
.card.signup-mode .panel-signin {
  opacity: 0;
  pointer-events: none;
  transform: translateX(-20px);
}

.card.signup-mode .panel-signup {
  opacity: 1;
  pointer-events: auto;
  transform: translateX(0);
}

.panel-inner {
  width: 320px;
}

.title {
  text-align: center;
  font-size: 26px;
  margin-bottom: 32px;
  font-weight: 500;
}

.login-tip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  margin-bottom: 16px;
  background: var(--bg-card, rgba(255, 255, 255, 0.85));
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.15);
}

.login-tip-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
}

.login-tip-text {
  font-size: 14px;
}

.login-tip--success {
  border: 1px solid rgba(46, 213, 115, 0.6);
}

.login-tip--success .login-tip-dot {
  background: #2ed573;
}

.login-tip--success .login-tip-text {
  color: #1e8449;
}

.login-tip--error {
  border: 1px solid rgba(250, 82, 82, 0.6);
}

.login-tip--error .login-tip-dot {
  background: #fa5252;
}

.login-tip--error .login-tip-text {
  color: #c92a2a;
}

.fade-enter-active,
.fade-leave-active {
  transition:
    opacity 0.3s ease,
    transform 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.forgot {
  display: block;
  margin-top: 4px;
  margin-bottom: 16px;
}

.btn-wrap {
  text-align: center;
  margin-top: 8px;
}

.primary-btn {
  padding-inline: 48px;
  background: linear-gradient(90deg, #0084b8, #00b894);
  border: none;
}

.primary-btn:hover {
  background: linear-gradient(90deg, #0073a1, #009f80);
}

.slider {
  position: absolute;
  inset: 0;
  display: flex;
  pointer-events: none;
}

.slider-bg {
  flex: 0 0 50%;
  background: transparent;
}

.slider-content {
  flex: 0 0 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: auto;
  transition: transform 0.6s ease;
}

.slider-content.to-right {
  transform: translateX(-100%);
}

.ghost-btn {
  padding-inline: 56px;
  background: rgba(0, 0, 0, 0.25);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.7);
}

.ghost-btn:hover {
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  border-color: #fff;
}

@media (max-width: 960px) {
  .card {
    width: 100%;
    height: 100vh;
    border-radius: 0;
  }

  .panel-inner {
    width: 280px;
  }
}
</style>

<style>
/* 登录成功弹窗 - 不受 scoped 限制，Teleport 到 body */
.login-success-overlay {
  position: fixed;
  inset: 0;
  z-index: 10000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  animation: overlay-in 0.4s ease-out;
}

.login-success-modal {
  padding: 40px 56px;
  background: rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.35);
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
  animation: modal-in 0.45s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.login-success-icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(46, 213, 115, 0.9), rgba(0, 184, 148, 0.9));
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 20px rgba(46, 213, 115, 0.4);
}

.login-success-icon .checkmark {
  color: #fff;
  font-size: 32px;
  font-weight: 700;
  line-height: 1;
}

.login-success-text {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: rgba(30, 41, 59, 0.95);
  text-shadow: 0 1px 0 rgba(255, 255, 255, 0.8);
  letter-spacing: 0.05em;
}

.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 0.35s ease;
}

.modal-fade-enter-active .login-success-modal,
.modal-fade-leave-active .login-success-modal {
  transition: transform 0.35s ease;
}

.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}

.modal-fade-enter-from .login-success-modal,
.modal-fade-leave-to .login-success-modal {
  transform: scale(0.9) translateY(-10px);
}

@keyframes overlay-in {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

@keyframes modal-in {
  from {
    opacity: 0;
    transform: scale(0.85) translateY(-20px);
  }

  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}
</style>
