# FancyPlugins 公开维护分支

本仓库 Fork 自 [FancyInnovations/FancyPlugins](https://github.com/FancyInnovations/FancyPlugins)，保留完整上游历史与 MIT 许可证。维护重点仅为 FancyNpcs 和 FancyNpcsModel 在 Folia/Luminol 1.21.11 环境中的稳定运行。

## 维护范围

| 组件 | 目标 |
| --- | --- |
| FancyNpcs | 正版皮肤解析、非阻塞换肤、Folia 调度安全 |
| FancyNpcsModel | BetterModel 模型显示、视野重载、交互、移动与发光状态 |
| 运行环境 | Minecraft 1.21.11、Java 25、Folia/Luminol |

其他 FancyInnovations 插件默认跟随上游，不在本 Fork 的主动维护范围内。

## 当前修复

### FancyNpcs

- `/npc skin <NPC> <正版用户名>` 不再在玩家所在的区域线程等待网络请求。
- SkinsRestorer、Eclipse、服务端 Profile 和 Mojang 查询在有界执行器中完成。
- 相同用户名与皮肤类型的并发查询共享结果，避免重复请求外部接口。
- 同一个 NPC 连续换肤时只允许最后一次请求生效。
- 结果通过 Folia 调度器回到玩家上下文后再触发事件和更新 NPC。
- 插件停止时取消未完成结果并关闭维护分支新增的查询执行器。
- Mojang Session API 地址允许通过 Java system property 覆盖，方便兼容代理或镜像环境。

### FancyNpcsModel

- 使用每个 NPC 一个 BetterModel DummyTracker、每位玩家独立显示状态，避免新玩家进入时让旧玩家的模型闪白。
- 玩家离开并重新进入可见距离后补发模型；不会用固定强制刷新破坏已显示玩家的模型。
- 模型启用时隐藏原版载体名称、实体和原版发光边框，发光状态交给模型。
- BetterModel 模型碰撞箱的左右键会继续触发 FancyNpcs 动作，并去除重复事件。
- NPC 移动、旋转、移除和 BetterModel 重载时清理或重建对应 tracker。
- 插件停止时关闭全部模型 tracker。

## 构建

Windows：

```powershell
.\gradlew.bat :plugins:fancynpcs-v2:shadowJar :plugins:fancynpcs-model:shadowJar
.\gradlew.bat test
```

Linux：

```bash
./gradlew :plugins:fancynpcs-v2:shadowJar :plugins:fancynpcs-model:shadowJar
./gradlew test
```

服务端运行和 Gradle 编译使用 Java 25。1.21.5、1.21.6 和 1.21.9 数据包模块仍以 Java 25 编译，但它们的 Paperweight `javaLauncher` 单独使用 Java 21，避免旧版 CodeBook 因无法读取 Java 25 runtime class 而失败。仓库的 toolchain resolver 会自动准备所需 JDK。生成物位于各模块的 `build/libs`，不要提交 `build` 目录。

根构建脚本仅为 1.21.11 dev-bundle 中缺失版本号的 `adventure-text-serializer-ansi` 固定 `4.26.1`；上游修复依赖元数据后应删除该约束。

Foojay toolchain resolver 使用 `1.0.0`，因为旧 `0.8.0` 引用了已被 Gradle 9 删除的 `JvmVendorSpec.IBM_SEMERU`。

## 发布前验证

1. 在隔离的 Luminol 1.21.11 服务端安装 FancyNpcs、FancyNpcsModel、BetterModel 和 SkinsRestorer。
2. 创建玩家类型 NPC，并套用一个 BetterModel 模型。
3. 连续执行五次未缓存的正版用户名换肤命令，然后立即执行一条标记命令；标记不应等待皮肤网络查询。
4. 两名玩家分别进入视野，确认新玩家加载模型时旧玩家不闪白。
5. 离开并重新进入加载距离，确认模型恢复且原版 NPC 不先出现。
6. 检查左右键动作、发光、`npc move_here`、动画和 BetterModel 重载。
7. 正常停止服务器，确认没有调度器、线程、ClassLoader 或 tracker 错误。

## 部署与回滚

- 更换 JAR 前完整停止子服，并备份当前 FancyNpcs 与 FancyNpcsModel。
- `plugins` 中每种插件只保留一个 JAR。
- 不使用 PlugManX 热替换这一组插件；正常启动后再验证 NPC。
- 回滚时停止服务器，恢复两份旧 JAR 并重新启动。配置和 NPC 数据不要删除。

## 同步上游

```bash
git remote add upstream https://github.com/FancyInnovations/FancyPlugins.git
git fetch upstream
git checkout main
git merge upstream/main
```

同步后重点检查 `SkinCMD`、`SkinManagerImpl`、`MojangAPI` 和 FancyNpcsModel tracker/listener 文件。若上游已经实现同等修复，应删除本 Fork 的重复代码，而不是维护两套实现。

## 提交问题

请使用仓库的 Bug 模板，并提供服务端核心完整版本、Java 版本、四个相关插件版本、复现步骤和脱敏后的相关日志。不要上传服务器地址、令牌、正版验证密钥或完整玩家数据库。
