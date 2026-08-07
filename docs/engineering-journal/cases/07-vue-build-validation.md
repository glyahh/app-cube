# Case 07: Vue build validation

## Phenomenon

模型生成的 Vue 代码看起来合理，但 SFC 结构、依赖或编译结果可能不合法。

## Root cause

聊天层的文本判断不能替代编译器和构建工具，生成、修复和发布之间缺少确定性门禁。

## Decision

先用 `@vue/compiler-sfc` 预解析，必要时进入修复，再二次检查并执行 `npm run build`；只有构建产物进入发布链路。

## Verification

- 源码关键词：`vueProjectBuilder`、`@vue/compiler-sfc`
- 关注：源码与 `dist` 制品边界、二次校验和构建退出码

## Residual risk

需要补充失败样本集、依赖安装失败和构建超时的可重复测试。

