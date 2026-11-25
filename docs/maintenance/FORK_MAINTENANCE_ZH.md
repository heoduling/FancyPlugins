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
- FancyNpcsModel 启用时清理同名旧属性注册，停用时移除本次注册实例，防止单独热重载模型插件时 FancyNpcs 的属性列表逐代增长。
- `1.1.0+12` 停用时注销 BetterModel 重载订阅，并从 JUL 日志中移除本实例的异常处理器。Shiroha 26.2 上完成了冷启动与模型绑定验证。附属单独重新启用时，仍会被 FancyNpcs 的 Cloud 命令注册阶段拒绝，因此此构建应通过完整停服启动来更换。
- `1.1.0+13` 可解析 `INTERACTION` 载体继承的 Bukkit 实体访问方法。FancyNpcs 退出时，附属关闭当前模型跟踪器、撤销本批属性和动画注册，并暂停模型事件；旧命令即使仍由 Cloud 路由，也只提示附属已停用。附属停用时结束自己的日志与统计资源，移除了无操作的 Paper 启动钩子。Shiroha 26.2 双客户端验证确认旧模型按实体 ID 移除，可在完整重启后恢复已保存的模型及动画动作。仍须完整停服启动来恢复集成，Cloud 独立热装限制与跨代依赖未解除。
- `1.1.0+14` 优先使用 FancyNpcs `2.11.0+370-6` 的可撤销扩展 API，各代主插件的模型处理代码独立加载。附属自行管理 `/fancynpcsmodel` 命令、帮助项及 Shiroha 26.2 启动登记；载体恢复在所属区域执行。载体重新发给玩家时，仅对该玩家补发一次模型，覆盖未发 Bukkit 跨世界事件的传送路径。旧主插件仍走冷启动注册流程。
- `1.1.0+15` 在 Shiroha 26.2 上卸载模型附属时恢复已绑定 NPC 的原版可见与发光状态，保留存档中的模型及动画绑定。加载后先向当前观看者发载体隐身状态，再发模型，不重建原版 NPC。管理帮助随附属重新登记，版本查询立即读取 JAR，已有异步检查结果时才附加更新提示。
- `1.1.0+16` 保留暂缺模型的绑定并恢复原版外观，资源回来后自动应用。NPC 可见距离变化会同步静止观看者，仅显式 `hitbox` 骨骼创建附属点击区，沿用 FancyNpcs 的动作及防重复触发。退出、删除及到期后回收点击和可见范围记录。
- `1.1.0+17` 点击区随区块卸载释放，在玩家回来后按骨骼补建。权限可见模式、同世界传送及玩家端区块送达后复核模型显示；`always_visible` 用玩家实际收到的区块判断，不再套 96 格距离，也不主动加载区块。
- `1.1.0+18` 定时在玩家区域复核已显示的权限模型，撤权时只移除该玩家的模型和点击区。模型附属独立管理内嵌统计 SDK 的 HTTP 客户端，停用时取消请求并拒绝再创建客户端。
- `1.1.0+19` 在 JAR 中同时提供 `plugin.yml` 和 `paper-plugin.yml`，让 PlugManX 3.2.1 能补全尚未加载的模型附属。两份描述共用构建版本；服务端仍优先使用 Paper 描述及其依赖设置。
- `1.1.0+20` 模型管理命令仍提供 `getPlugin()`，但不把插件实例保存为命令字段，避免先卸 FancyNpcs 时 PlugManX 的反射缓存留住旧附属类加载器。
- `1.1.0+21` 内置简体中文。新装默认 `zh_cn`，旧 `settings.language: default` 也按中文显示；已有中文文件只在内存中补齐缺少的提示。显式选择不改写，内置英文可选 `en_us`。管理及模型命令从当前翻译器取提示，配置重载后立即切换语言。
- `1.1.0+22` 模型沿用 NPC 的头顶名称和格式，按观看者显示在模型上方，改名不重建模型。取下模型或卸载附属时清理模型名牌，让原版载体恢复显示。
- `1.1.0+23` OP 可用 `/fancynpcsmodel public <NPC 名称或 UUID> <on|off|status>` 管理公开模型交互，默认关闭。选择按 NPC UUID 持久化，只在该 NPC 自有点击框标记 NPC 身份；Lands 的荒野权限、其他实体、事件取消和 NPC 冷却均不改动。开启前确认该 NPC 上所有访客可触发的动作都允许公开使用。
- `1.1.0+24` 公开交互按 FancyNpcs 存档的原始内部编号识别，兼容文本 ID 的导入 NPC。已有标准小写 UUID 必须沿用旧标记文件；其他内部编号按原始数据生成独立摘要文件名，区分大小写。指令可用名称或内部编号，精确编号优先。
- `1.1.0+25` 从 +23 直接升级时，等待 FancyNpcs 加载完，再后台迁移只对应一个大写 UUID 的旧公开标记。大小写冲突时保留原小写记录，不替其他 NPC 开启公开。已经运行过 +24 的服不会用遗留小写文件恢复权限，避免把管理员撤销的权限重新打开。
- `1.1.0+26` 从 +23 直接升级时，未加载世界中归属唯一的存档 NPC 也能迁移公开设置。世界卸载时提前取消该世界的点击框跟随任务并移除标记，再由所属 Folia 区域移除临时实体；不删除 NPC 存档和已确认的公开设置。
- `1.1.0+26` FancyNpcs 重读存档并替换 NPC 实例时重建对应模型跟踪器，避免复用仍指向旧世界或旧 NPC 的点击框；仅玩家进出视野时仍复用模型。
- `1.1.0+27` 名牌优先使用唯一的 `fnm_nameplate` 骨骼原点；未设置时取可靠单一主体点击框或待机可见几何的上沿，原有 0.25 格留白只加一次。按 BetterModel 渲染器缓存测量，不改点击框或动画播放。
- `1.1.0+28` 测量立方体时仅计入带贴图面的角，不把没画出来的顶部算作模型高度。手工 `fnm_nameplate` 按展示实体生成包的偏航角定位（负角也向下取整），避免偏心名牌在非整方向与模型错位。
- `1.1.0+29` `/npc play_animation … --loop` 和 NPC 的 `play_animation_loop` 动作显式使用 BetterModel 的循环模式，不再沿用模型资源声明的播放方式。无标志的播放命令和 `play_animation_once` 仍只播一次。
- `1.1.0+30` 世界卸载时先按原流程移除点击框，再按本次卸载的世界实例清除对应的模型跟踪、名牌与 BetterModel 跟踪器（含无点击框的模型）。拒绝为正在卸载或已失效的世界实例创建跟踪器；不影响其他世界，也不清除 NPC 存档或公开交互设置。

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
