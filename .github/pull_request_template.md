## 变更内容

<!-- 做了什么、为什么。涉及真机现象的，写明设备与系统版本。 -->

## 影响范围

- [ ] `lumen-engine`
- [ ] `lumen-motion`
- [ ] `lumen-controls`
- [ ] `sample` / 文档 / 工具

## 自查（docs/VERSIONING.md §5、docs/ENGINEERING_RULES.md）

- [ ] 构建门禁通过：`./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon`
- [ ] 公开 API 有变化时已同步 `docs/API.md`，并按 `VERSIONING.md` 判断了版本位
- [ ] 适配标准有变化时已同步 `docs/INTEGRATION_STANDARD.md`（节号只增不改）
- [ ] `CHANGELOG.md` 的「未发布」已记录
- [ ] 涉及高于 minSdk 的平台类型：已放进 `*ApiNN` 隔离类，并跑过 DEX 审计（§2.4）
- [ ] 新增的策略或真机教训已落成 JVM 测试或源码契约测试（§8）
