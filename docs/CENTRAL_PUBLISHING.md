# 发布到 Maven Central

本引擎目前通过 JitPack（`com.github.jichuo1.LumenCoacervationEngine:<模块>:<版本>`）和 Release 附件里的 Maven 仓库压缩包（`com.lumen.coacervation.engine`）分发。Maven Central 是第三条渠道，坐标用已验证的命名空间 `io.github.jichuo1`：

```kotlin
implementation("io.github.jichuo1:lumen-engine:<版本>")
```

仓库里的源码、默认 `lumen.group` 和 JitPack 坐标都不变；Central 的坐标只在构建时用 `-Plumen.group=io.github.jichuo1` 覆盖。

## 分工

| 步骤 | 谁做 | 在哪里 |
|---|---|---|
| 构建九个模块、补 POM 元数据、生成 javadoc 占位包、重算校验和、产出未签名 bundle | CI | `.github/workflows/central-bundle.yml` + `tools/central_bundle.py` |
| 用私钥给每个文件生成 `.asc` | 维护者 | 本机，`tools/central_bundle.py sign` |
| 在 Central Portal 上传、校验、发布 | 维护者 | https://central.sonatype.com |

私钥与口令不进仓库也不进 CI。

## 一次性准备

以下按 Sonatype 文档（2026-10-30 核对；要求可能变化，以官方文档为准）：

1. 注册 Central Portal 账号。
2. 验证命名空间 `io.github.jichuo1`：用 GitHub 账号注册时可能已自动授予；否则在 Portal 的 “View Namespaces → Add Namespace” 添加，复制 Verification Key，在 GitHub 上建一个以该 Key 命名的公开仓库（`github.com/jichuo1/<key>`），回 Portal 点 “Verify Namespace → Confirm”；通过后可以删掉这个临时仓库。
3. 准备 GPG 密钥：`gpg --full-generate-key`，设置口令；把公钥发布到 Central 会检查的公钥服务器之一（`keyserver.ubuntu.com`、`keys.openpgp.org`、`pgp.mit.edu`），例如 `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`。密钥到期要在发布前延期并重新发布公钥。

## 每次发布

先按 `VERSIONING.md` §5 和 `release-assets.yml` 发出不可变的 tag 与 Release，然后：

1. 运行 `Central bundle` 工作流（只能在 main 上）：

   ```bash
   gh workflow run central-bundle.yml --repo jichuo1/LumenCoacervationEngine -f tag=1.6.1
   ```

   默认 `group=io.github.jichuo1`。它会从 tag 检出源码、运行本工具的单元测试、构建九个模块、打包并校验，上传 artifact `central-bundle-<tag>`（保留 14 天）。
2. 下载未签名的 bundle：

   ```bash
   gh run download <run-id> --repo jichuo1/LumenCoacervationEngine -n central-bundle-1.6.1
   ```

3. 在本机签名（需要 GnuPG；口令由 gpg-agent 向你询问）：

   ```bash
   python tools/central_bundle.py sign --bundle lumen-central-1.6.1-unsigned.zip --group io.github.jichuo1 --tag 1.6.1 --key <KEYID或邮箱> --output lumen-central-1.6.1-signed.zip
   ```

   签完会用 `gpg --verify` 逐个核对 `.asc`，并打印所用密钥的指纹。
4. 在 Central Portal 的 Publish 页上传 `lumen-central-1.6.1-signed.zip`，等校验通过后发布。

## bundle 的内容

每个模块目录 `io/github/jichuo1/<模块>/<版本>/` 下有五个文件，每个文件带 `.md5`、`.sha1`、`.sha256`、`.sha512`，签名后再各带一个 `.asc`（`.asc` 和校验和文件本身没有校验和，这是 Central 的要求）：

- `<模块>-<版本>.aar`、`.module`（Gradle 元数据）、`-sources.jar`：原样取自该 tag 的构建；
- `<模块>-<版本>.pom`：Gradle 生成的 POM 加上 `url`、`developers`、`scm`，其余逐字保留（包括 Gradle 使用者靠它优先读取 `.module` 的标记注释）；
- `<模块>-<版本>-javadoc.jar`：占位包。库是 Kotlin 写的，不生成 Javadoc；包里的 `README.txt` 指向该 tag 的 `docs/API.md`。Sonatype 允许占位包通过校验。

九个模块必须一起上传。依赖其他模块的 POM 里，模块间依赖的 groupId 都是 `io.github.jichuo1`。

## 限制与边界

- 已发布到 Central 的组件不能修改，修复要发新版本（Sonatype 的政策）。上传前先确认 tag 与版本。
- Central 坐标与 Release 附件里的 `lumen-maven-<版本>.zip` 是同一 tag 的两次独立构建，字节不保证相同。
- 本工具与工作流只覆盖 bundle 的生成、校验与签名；上传到 Central 需要你的 Portal 账号，我们没有自动化它，也不存放 Portal Token。
- 工作流的 PR 演练用已发布的 1.6.0 tag 验证流程本身，并不代表 1.6.0 应该发到 Central。
