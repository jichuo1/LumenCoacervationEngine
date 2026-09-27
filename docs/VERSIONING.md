# 版本规则

引擎同时维护两个版本号：

| 版本号 | 位置 | 何时变 |
|---|---|---|
| 引擎版本 `LumenEngine.VERSION` | `gradle.properties` 的 `lumen.version`；Maven 坐标的版本 | 每次发布 |
| 契约版本 `LumenEngine.CONTRACT_VERSION` | `LumenEngine.kt` | `GlowEngine` 契约或适配标准有**不兼容**变化时 |

## 1. 引擎版本：语义化版本 `主.次.修订`

三个库产物（`lumen-engine`、`lumen-motion`、`lumen-controls`）共用同一个版本号，一起发布；宿主**应当**使用相同版本的三者。

| 变化 | 升哪一位 | 例子 |
|---|---|---|
| 公开 API 的不兼容变化 | **主版本** | 删除或重命名 `API.md` 里的声明；改变参数语义；提高 `minSdk` |
| 适配标准新增或收紧**必须**条款 | **主版本** | 新增一个宿主必须转发的回调 |
| 契约版本递增 | **主版本** | 见 §2 |
| 新增公开 API、新增**应当**/**可以**条款 | 次版本 | 新增表面角色的委托方法；开放材质注册入口 |
| 缺陷修复、性能优化、视觉调参 | 修订 | 行为与适配标准保持一致的内部修改 |

- 兼容承诺**只**覆盖 `API.md` 列出的声明，以及 `INTEGRATION_STANDARD.md` 的条款。
- `internal` 声明、未列入 `API.md` 的公开辅助成员、源码契约测试，都不在承诺范围内。
- 视觉调参（颜色、模糊半径、光学强度）属于修订：同一宿主在修订版本之间的画面允许有细微差异，但不得出现结构性变化，例如表面缺失、层级错乱。

## 2. 契约版本

`CONTRACT_VERSION` 只在以下情况递增，并且同时升主版本：

- `GlowEngine` 接口的不兼容变化：新增没有默认实现的抽象成员、改变已有成员的语义；
- 适配标准里某条**必须**条款的时机或语义发生变化，例如某个回调从"在 super 之前"改为"之后"。

宿主**可以**在接入层断言契约版本，这样引擎升级后如果契约变了，会在开发期就暴露出来，而不是静默改变行为：

```kotlin
check(LumenEngine.CONTRACT_VERSION == 1) { "凝光视效引擎契约已变化，请按 CHANGELOG 复核接入" }
```

## 3. 持久化协议

以下几项属于**用户数据协议**，任何版本都不得不兼容地改变：

- `SkinId.storageValue`；
- 偏好文件内的键；
- 背景配置的 schema 版本。

需要演进时，必须新增值或新增 schema 版本，并保留对旧数据的读取。

渲染器版本号（`SkinRepository` 内的 `CURRENT_LIQUID_RENDERER_VERSION`）是内部协议，与引擎版本无关。高级材质的渲染协议有不兼容变化时递增它，所有设备就会重新走一遍健康确认。

## 4. 适配标准的节号

`INTEGRATION_STANDARD.md` 的节号只增不改：

- 代码注释和测试都按节号引用；
- 废弃的条款标注"已废弃（自 x.y）"并保留编号；
- 新条款追加在所属章节的末尾。

## 5. 发布检查

1. 更新 `gradle.properties` 的 `lumen.version` 和 `CHANGELOG.md`。
2. 如有 API 变化，同步 `API.md`；如有标准变化，同步 `INTEGRATION_STANDARD.md`。
3. 跑构建门禁（`ENGINEERING_RULES.md` §11）和 DEX 审计（§2.4）。
4. `./gradlew publishAllPublicationsToProjectLocalRepository --no-daemon`，产物在 `build/repo`。
