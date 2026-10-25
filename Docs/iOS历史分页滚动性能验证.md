# iOS 历史分页滚动性能验证

> ⚠️ **历史存档 · 取证路径已失效**：本文中的 `/tmp/...`、`/Users/...` 等链接是当时的临时取证产物，
> 早已清理或属于他人机器路径，**点开必然 404**。这是历史原貌，不是文档缺陷；
> 结论仍可参考，但**不要指望顺着链接找到当时的日志/截图/构建产物**。

## 最新改动：准备完成后渐显正文

参考用户提供的 DeepSeek [会话切换录像](</Users/lichaofan/Downloads/ScreenRecording_10-21-2026 11-59-14_1.mov>)：可见正文在固定位置由浅到深呈现；录像只能证明会话切换的视觉行为，不能据此确认其内部历史分页算法。本次为我们自己的 UIKit viewport 加入相应呈现策略，没有更换 Markdown 引擎或降低原有锚点要求。

进入会话时，旧会话立即隐藏；新内容在不可见但参与布局的 viewport 中完成 snapshot、测量和初始定位。连续两个显示 tick 的几何稳定后，以180ms透明度动画整体呈现。首屏渐显使用独立的短期显示回调，不占用历史批次队列，流式 token 继续沿原有轻量路径更新；生成中的首屏只要求视口约束稳定，不等待回复停止增长。

加载历史时，旧正文保持显示和原有位置补偿；新增正文在分批提交中保留尺寸、暂不显示，待当前历史目标完整提交、对应请求 terminal 到达及几何稳定后统一渐显当前可见的新项。屏幕外的新项之后正常显示，不在用户随后翻到它时补做动画。新增内容恢复模型层可见性后才确认历史呈现、释放准入，滚动条继续等几何稳定后恢复。同一历史 epoch 的持续 live 不能让最终渐显和确认饥饿，新历史 epoch 仍优先准备。

用户开始新拖动时，已经插入的内容立即正常显示，取消首屏或单元格的活动渐显，后续历史准备仍按滚动优先策略暂停；不以透明空白行阻碍继续阅读。动画只修改合成透明度，不移动行、不再次测高。单元格离屏、再次显示、会话切换和卸载均清理透明状态；等待期间隐藏不可见正文的辅助功能元素及点击目标。系统启用“减弱动态效果”时直接显示，动态开启后也会终止活动渐显。

[最终原生回归](/tmp/dsh-history-reveal/complete/regression-summary.json)79/79通过、0失败、0跳过，包含新增6项用例：首屏提交和定位前不得曝光、历史仅新正文渐显且可见后确认、网络尚未结束时新拖动立即释放透明行、减弱动态效果及会话切换、空会话后来收到内容、首屏持续流式更新。已有持续 live 不饥饿、可见窗口补偿、旧截图闪回、滚动条、Markdown与流式布局断言均保留并通过。首轮实现曾使原有流式回归失败，已将视觉等待与数据更新解耦；延迟确认曾触发同 epoch live 饥饿，已先完成当前已提交历史的视觉收尾，失败轮次原日志均保留于 `/tmp/dsh-history-reveal/`。本次没有改分析器、测量合同或性能门槛。

[最终签名构建](/tmp/dsh-history-reveal/complete/build.log)、[严格签名校验](/tmp/dsh-history-reveal/complete/codesign.log)、[不定义DEBUG的主模块类型检查](/tmp/dsh-history-reveal/complete/nondebug-typecheck.log)均通过；类型检查复用Debug依赖，并非完整Release打包。[源码指纹](/tmp/dsh-history-reveal/complete/source-manifest.json)、[包与二进制证明](/tmp/dsh-history-reveal/complete/build-proof.json)及[签名模拟器Debug包](/tmp/dsh-history-reveal/complete/DshMobile-signed-Simulator-Debug.app.zip)独立归档。

同包在既有真实会话完成30次上翻，[原始采样](/tmp/dsh-history-reveal/real-session/capture/raw.json)确认一轮完整operation、实际新增9项、有效锚点误差0pt，[安装二进制](/tmp/dsh-history-reveal/real-session/capture/capture-proof.json)与归档包一致。用户继续拖动时新项已立即可读，最终视觉收尾日志如实记录 `kind=history rows=0`，没有再次淡入旧正文；暂停阅读时新增可见项的渐显由上述原生集成用例覆盖，不把这一轮说成实录展示了新行淡入。

[完整录屏](/tmp/dsh-history-reveal/real-session/screen.mov)按真实编码时间逐帧检查：11.4–13.5秒的119帧涵盖首屏，约12.96–13.13秒呈现固定位置由浅到深的正文；[首屏演示片段](/tmp/dsh-history-reveal/real-session/initial-fade-demo.mp4)已截取。日志墙钟与视频存在约1.5秒偏移，初次墙钟定位的首屏窗口没有编码帧，原结果保留，没有当作通过。随后按可见转场定位，历史合并附近34.8–35.8秒全部55帧也保留；可见分组头9→33后继续正常阅读，未观察到旧截图闪回。[检查说明](/tmp/dsh-history-reveal/real-session/visual-inspection-final.json)明确其为视觉观察与估计对齐，不冒充精确物理呈现时间测量。

本轮带录屏、查询式手势的[完整性能报告](/tmp/dsh-history-reveal/real-session/capture/summary.json)中，experience、display-cadence与callback Gate仍均为fail；全部统计保留。这次结论限于渐显与定位行为，未声称帧率达标或GPU FPS，也不覆盖下方旧版无录屏采样结果。没有发送消息、清数据库或变更配对。

## 最新修正：历史合并时旧截图短暂闪回

用户[原始录像](</Users/lichaofan/Desktop/Simulator Screen Recording - iPhone 17 - 2026-10-21 at 11.52.16.mov>)共474个编码帧。逐帧检查发现第274帧（3.880s）整块聊天窗口切到更早的截图与正文，第275帧（3.921667s）又回原阅读位置，约41.7ms；导航栏未切换。这是提交期间的可见窗口变化，不能仅以最终锚点正确判定无闪回。

录像对应的既有会话实测确实触发`replacement`：一个历史operation整批新增9项，保留旧行的revision无变化。此前原生位置补偿仅覆盖严格`prepend`，这种历史合并会先以旧offset展示新布局，等completion再恢复。未修复产品代码上的新增原生测试复现了同样现象：新项均为文字，手动恢复前却出现屏幕外的旧截图；三个场景的锚点暂时偏移约1040/1100/1220pt，最终恢复后仍能达到原来的1pt门槛。[失败回归与日志](/tmp/dsh-history-flicker/native-red/test.xcresult)保留。

修复改按“被保留的可见正文窗口”判断是否可原生保位：它必须仍连续、同序且几何稳定，库所锚定的旧末行必须保留；允许屏幕外分组替换、屏幕外行增高，以及仅补齐图片字节而未改变预留尺寸。历史合并替换窗口顶部的旧分组头时，可以保住后续仍在阅读的正文。仍绑定完整异步事务UUID，保留原有手动锚点校正、新手势优先及跟尾状态保护。短内容、窗口内部删除/重排、可见行实际增高等情况仍保留原有回退，不将其泛化为本次已验证的无闪回场景。分页分类仍如实报告replacement，不把整批替换伪称每批一项。

[最终73项回归](/tmp/dsh-history-flicker/final-visible-window/regression-summary.json)全部通过、0失败、0跳过。新增一个用例覆盖四种情形：屏幕外替换、顶部旧分组头合并、旧截图字节补齐、屏幕外行实际增高。每种都在手动恢复之前连续观察3个实际显示tick，检查旧截图不进入窗口、保留正文的可见顺序与固定锚点误差≤1pt；前三种未修复产品失败的原断言均保留。已有可见行真实增高、短内容、新手势、旧会话退出、滚动条及Markdown测试一并通过。[非DEBUG主模块类型检查](/tmp/dsh-history-flicker/final-visible-window/nondebug-typecheck.log)也通过。

修复后的同一会话进行了30次真实上翻，[原始诊断](/tmp/dsh-history-flicker/fixed-real30/capture/raw.json)确认1个完整operation、replacement新增9项、有效锚点误差0pt；提交elapsed约7.84ms，仅是本轮墙钟记录，不计算跨轮速度比。[录屏](/tmp/dsh-history-flicker/fixed-real30/screen.mov)提交附近1秒的56个编码帧全部保留并逐帧核查，未观察到旧截图闪回；[帧检查说明](/tmp/dsh-history-flicker/fixed-real30/commit-frame-inspection.json)中红色截图标记计数只是旁证，不能替代原生可见ID/锚点断言。录像时码通过启动墙钟与匿名提交日志近似对齐，非物理呈现时间测量。

前后两次30手势录屏诊断的帧率Gate均为fail，[修复前报告](/tmp/dsh-history-flicker/baseline-real30/capture/summary.json)与[修复后报告](/tmp/dsh-history-flicker/fixed-real30/capture/summary.json)完整保留；屏幕编码与查询式手势条件不用于替代此前无录屏的滚动验收。另一次较短驱动未触发分页，明确为证据不足，未作通过。此次结论限于窗口闪回修复，未声称新增帧率成绩或GPU FPS。

[源码指纹](/tmp/dsh-history-flicker/final-visible-window/source-manifest.json)、[签名构建证明](/tmp/dsh-history-flicker/final-visible-window/build-proof.json)、[安装二进制校验](/tmp/dsh-history-flicker/fixed-real30/capture/capture-proof.json)和[新版签名模拟器Debug包](/tmp/dsh-history-flicker/final-visible-window/DshMobile-signed-Simulator-Debug.app.zip)已归档。会话ID、连接URL未写入报告；配对与数据库保留，没有发送消息或修改会话。

## 后续修正：历史加载期间隐藏滚动条

用户反馈系统纵向滚动条会随历史分批插入反复跳动。现在从历史请求实际开始时隐藏，网络结束、中间展示批、锚点校正及中途再次拖动期间保持隐藏；最终operation完成、待呈现工作排空，且连续两个空闲显示tick的内容尺寸、偏移、可视尺寸和inset相同后，再恢复系统滚动条的正常显示策略，不主动闪现。恢复沿用原有空闲调度器，不额外扫描列表或测高。

生命周期接点已移出DEBUG条件，正式版本也执行相同显示逻辑；Debug帧率记录仍留在DEBUG内。以operation UUID隔离旧完成回调，新请求开始会重新隐藏，失败/空页可在稳定后恢复，离开会话会清理状态。

[本次原生回归](/tmp/dsh-history-scroll-indicator/regression-summary.json)72/72通过、0失败、0跳过，包含3项新增真实UIKit与准入集成测试，验证网络结束不能提前恢复、partial批隐藏、提交后新手势延期收尾、失败/空页/旧完成回调/离开清理。签名Debug构建与严格签名校验通过；另有[不定义DEBUG的主模块类型检查](/tmp/dsh-history-scroll-indicator/nondebug-typecheck.log)通过，复用已构建依赖，并非完整Release打包。

[本次源码指纹](/tmp/dsh-history-scroll-indicator/source-manifest.json)、[二进制指纹](/tmp/dsh-history-scroll-indicator/binary-proof.json)和[签名模拟器Debug包](/tmp/dsh-history-scroll-indicator/DshMobile-signed-Simulator-Debug.app.zip)独立归档。下面三轮性能数据属于此前滚动调度检查点，保持其原始源码/二进制与结果，不把本次UI回归当作新的帧率成绩。

## 当前结果：滚动优先策略，最后两轮连续通过

2026-10-21，按用户最新“保留当前阅读内容，停稳后受控呈现历史”的策略，采用采样前已冻结的独立 `interaction-priority` 合同。相同签名模拟器 Debug 包完成三轮真实既有 Session 测试，最后两轮连续通过。首轮 1.005% 的临界失败完整保留，不四舍五入成通过。旧 `display-cadence` 与 `legacy-arrival` 全管线报告仍为失败，未被新体验结果覆盖；这些是模拟器显示时间戳与回调统计，GPU FPS 仍不支持测量。

当前实现为：拖动、触摸跟踪及减速时保留已显示 snapshot，最新目标仅缓冲一份；连续停稳约120ms后每tick最多准备1行，下一tick才提交，每批最多新增1展示行。准备/呈现期间保持原历史请求占用，partial批不确认完成；新手势暂停后续工作，最终required epoch真正完整呈现后才释放。单个同步测高不能抢占，不声称单行必在16.7ms内完成。

普通历史前插额外使用 ChatLayout 原生位置补偿，防止等completion才纠正窗口：仅旧行无revision变化、有效阅读锚点、旧内容高于可视区、bottom inset不变且非跟尾/展开时启用。补偿绑定真实事务UUID，覆盖异步UIKit完成及延期收尾；短内容、replacement和旧可见行真实变高仍走原手动校正。新手势优先，并在停稳后以用户新的阅读位置收尾。WARM实验保持默认关闭，MarkdownUI与主App仍为 `-Onone -DDEBUG -g`。

新增混合高度测试曾发现候选产品问题：无手势时原生补偿以临时contentSize触发pinned从false变true，随后更新跟到底部，固定锚误差-176pt；该失败与pinned附件保留于[候选检查点](/tmp/dsh-scroll-native-offset-checkpoint)。最终仅在补偿事务内忽略无真实手势的中间跟尾判定，稳定几何后重新校准。真实用户拖到底部/离开底部仍改变状态，退出/旧会话回调只清匹配UUID。另在最终测试中等待原本异步的pinned回调再作断言，所有原断言保留；没有新增产品观察器。

[最终69项原生回归](/tmp/dsh-scroll-native-offset-final-checkpoint/regression-summary.json)为69/69通过、0失败、0跳过，覆盖每批实际snapshot差集≤1、手动restore之前的固定阅读锚点与可见IDs、加载提示/缓存命中/长Markdown、旧行真实60→90pt的手动fallback、提交中再次拖动、同epoch实时消息、宽度/空页/切会话和最终确认。冻结Python分析器最终115/115通过；三轮raw另行完整重算，所有summary字段相等，详见[结果审计](/tmp/dsh-scroll-native-offset-final-checkpoint/final-results-audit.json)。

真实驱动为2个cycle共6次公开坐标拖动，包含短暂停顿、再次滑动及最后8s完成机会。每轮均观察到6个实际UIKit滚动窗口、4次实际调度中断、历史管线与滚动重叠、20批×1新增正文行、20次有效锚误差≤1pt、最新epoch4实际确认及operation完成；没有截断、未完成或滚动期间prepare/commit准入。每轮启动进程保留磁盘/配对，不发送消息。raw未记录请求来源，不据此宣称每次均为Room命中。

| 同包轮次 | 新体验Gate | 滚动display P95 | >33.3ms | 滚动display最大 | 全活跃arrival最大 / ≥100ms数 | 最大批呈现elapsed | 证据 |
|---|---|---|---|---|---|---|---|
| 1 | fail | 16.667ms | 8/796（1.005%） | 79.074ms | 89.818ms / 0 | 44.376ms | [完整报告](/tmp/dsh-scroll-native-offset-real-run1/summary-interaction-priority.json) / [原始数据](/tmp/dsh-scroll-native-offset-real-run1/raw.json) |
| 2 | pass | 16.667ms | 7/798（0.877%） | 81.452ms | 79.532ms / 0 | 36.372ms | [完整报告](/tmp/dsh-scroll-native-offset-real-run2/summary-interaction-priority.json) / [原始数据](/tmp/dsh-scroll-native-offset-real-run2/raw.json) |
| 3 | pass | 16.667ms | 6/799（0.751%） | 98.806ms | 70.744ms / 0 | 14.722ms | [完整报告](/tmp/dsh-scroll-native-offset-real-run3/summary-interaction-priority.json) / [原始数据](/tmp/dsh-scroll-native-offset-real-run3/raw.json) |

第二/三轮首正文出现分别距请求1.902s/1.904s，最终完整呈现13.150s/13.317s，包含持续手势和调度等待；距最后实际滚动结束分别约0.385s/0.551s完成剩余呈现。idle display P95为39.861ms/42.684ms，最大69.089ms/68.698ms；全活跃arrival及display均无≥100ms。第3轮滚动display最大98.806ms，仍接近100ms边界；结论限于本次场景的最后两轮通过，不代表任意会话、手势或真机GPU均稳定60FPS。提交elapsed不是CPU时间，不从不同轮次推算精确加速百分比。

同一 `DshMobile.debug.dylib` SHA为 `35e0a36d1627459138bd51d513eca3c286ddbfeaa6da5e56f8138a0f870c0c61`，三轮均核对已安装二进制、唯一日志PID、原始数据SHA、冻结源码和驱动配置。[构建与签名证据](/tmp/dsh-scroll-native-offset-final-checkpoint/build-proof.json)、[最终源码清单](/tmp/dsh-scroll-native-offset-final-checkpoint/source-manifest-final.json)、[签名模拟器Debug包](/tmp/dsh-scroll-native-offset-final-checkpoint/DshMobile-signed-Simulator-Debug.app.zip)与三轮各自的CSV、xcresult、匿名日志、意图附件均已归档。此次使用额度限制后由主代理接管最终构建/测试，未并发驱动模拟器，未提交或推送代码。

## 前期阶段记录（保留原始结论）

截至 2026-10-21，用户已明确批准采用`display-cadence`验收，并保留全部arrival统计及长停顿约束。阶段5优化包与原始基线已使用同版schema3完成首轮真实会话测量；对原raw按新策略另存重算后，两组仍均为有效失败，尚未达标。阶段5功能回归单批221/221、当前分析器72/72已通过；阶段6的root复用候选因真实诊断未命中而撤回，没有正式性能成绩。两种独立调度参考已保留，新的warm-host转移实验已观察到真实命中，但短测仍未通过性能门槛。后续继续通过独立调用栈诊断定位残余停顿。

## 测量条件

- 设备：iPhone 17、iOS 26.5 Simulator，UDID `BB56B073-C41F-4984-A3DA-54E14E71E1FB`。用户已明确选择模拟器。
- 包：标准 Xcode ad-hoc 签名的 Debug `.app`，`codesign --verify --deep --strict` 通过。Swift 主模块与 MarkdownUI 使用 `-Onone -DDEBUG -g`；KMP 为 `iosSimulatorArm64/debugFramework`，未额外覆盖 Native 优化选项。此结果不能称为 Release 性能。
- 数据：已存在的真实网关、真实已结束会话，匿名 hash `e7b510cc1801`。不创建消息、不发送消息、不删会话、不清数据库或 Keychain。每组正常重启 App 清进程内缓存，保留用户磁盘数据。
- 旧轮次初始磁盘窗口：400 events，seq 4946–5345，JSON 554,583 字符，`hasMore=true`。schema3首轮基线仍为400 events，阶段5优化包起始为73 events，详见下方独立缓存统计。实际前插显示行数会随异步快照而变化；旧第一轮诊断同样出现过 +62 行。因此 +55 与 +62 的不同不始于第二轮桥接优化；新轮次起始窗口和实际工作量也不同，均不能据此计算严格等负载的百分比加速。
- 手势：`XCUIApplication.open(URL)` 打开已有会话，严格断言 `conversation-history-viewport` 存在并含 cells；固定首屏等待 2 秒，再执行 30 次真实 `swipeDown(.fast)` 阅读更早历史。仅驱动测试通过不等于性能通过。
- 计时期间不并发编译、Gradle 链接、Profiler 或其它设备驱动。

Simulator 的 Animation Hitches 返回 `Hitches is not supported on this platform`，Core Animation 图形仪器返回 `The graphics instruments do not support the current device`。因此以下报告保留 **CADisplayLink 主线程回调到达间隔与display timestamp间隔**，不是 GPU/rendered/presentation FPS。详见[Hitches 能力探测](/tmp/dsh-scroll-simulator-capability-check.log)与[图形仪器探测](/tmp/dsh-scroll-simulator-fps-capability-check.log)。

> 测量仪器审计补充：下表保留原schema2输出及当时结论。后续审计发现“首个commit结束即清ordinal”不能独立证明多页operation完整生命周期。schema3最初通过57项分析器测试，现连同验收策略边界扩展至72项，逐一记录实际准入、required/issued/committed/presented epoch、KMP terminal及operation完成。下表“有效 fail”仅指旧schema2口径下可评估且失败，不构成schema3的最终完整生命周期证据。旧数据仍证明存在真实长停顿；最终两包须以同版schema3重测，不能由旧表推导新仪器已经通过。

## 固定门槛与证据要求

当前用户批准的`display-cadence`策略以可比较的display timestamp间隔检查P95 ≤16.7ms、超过33.3ms的比例 ≤1%；arrival和display两套间隔各自均不得出现 ≥100ms 的停顿，前插后阅读锚点误差 ≤1 UIKit point。阈值数值保持不变。全活跃区间、每个operation从首次实际准入到完成后完整1秒settle的窗口，以及每次commit后的完整1秒settle窗口，均须满足门槛。operation只有在KMP terminal已到达、最新required历史投影经过真实timeline issued、viewport committed及presented确认后才能完成；首个commit或网络loading结束不能提前关闭窗口。旧`legacy-arrival`策略仍保留原arrival P95、比例及长停顿计算，用于复核旧报告。

报告还必须将每次实际准入与各次commit关联到同一operation。一个operation可以包含多个内部页及多次commit；被动观察的基线还可包含原逻辑重复接受的调用，须保留accepted及coalesced计数，优化包的准入模式不允许重复接受。至少一项正数 `insertedRows` commit、正数前插operation内的滚动与pending重叠、每次commit的有效锚点和完整settle、最终epoch确认及完整生命周期，都是验收必需证据。纯空页不能替代正数前插。仅触顶不代表发起加载，`loading` 指实际准入后直到terminal与最终呈现均完成的分页管线，不代表纯网络耗时。任何样本、生命周期事件或诊断聚合截断，以及未完成管线，均不能作通过处理；活跃尾部仍按既定阈值检查，`insufficient-evidence` 不得作通过处理。

最终采样器从首次用户拖动开始。若首滑时已有未完成的呈现管线，记录 `initialPipelineInFlight=true` 并拒绝验收。首个回调保留从首滑到回调到达的真实等待；缺少前一个 display timestamp 时显式留空，不伪造正常帧。新策略下非首个可比较display间隔缺失或为0均判证据不足，负数或非有限数明确报错，不将其当作健康帧。长停顿不剔除、不截短。P95 采用 nearest-rank：排序后第 `ceil(n×0.95)` 个样本。

## 已取得的结果

下表的 P95、最大停顿、长停顿数均为“加载管线及 settle”窗口。阶段1为 Markdown/文件预处理有界缓存优化；阶段2在其上移除历史、会话投影与列表路径的多次全文 JSON 跨桥往返；阶段3把静态历史投影准备移至后台，MainActor仍负责代次校验及提交；阶段4进一步把纯页面解析并入后台值准备，保留MainActor上的token/reducer与合并。

| 包与采样 | 门禁结论 | 分页 P95 | 分页最大回调间隔 | 分页 ≥100ms | 实际前插 | 证据 |
|---|---|---:|---:|---:|---|---|
| 原始基线 run1 | **有效 fail** | 975.90ms | 1523.44ms | 15 | +55行 | [报告](/tmp/dsh-scroll-baseline-run1/summary.json) / [原始JSON](/tmp/dsh-scroll-baseline-run1/raw.json) / [CSV](/tmp/dsh-scroll-baseline-run1/raw.csv) / [context](/tmp/dsh-scroll-baseline-run1/context.json) |
| 原始基线 run2，首滑起采 | **证据不足，且已见明显停顿** | 940.94ms | 1472.13ms | 15 | +55行 | [报告](/tmp/dsh-scroll-baseline-run2/summary.json) / [原始JSON](/tmp/dsh-scroll-baseline-run2/raw.json) / [CSV](/tmp/dsh-scroll-baseline-run2/raw.csv) / [context](/tmp/dsh-scroll-baseline-run2/context.json) |
| 阶段1 run1 | **证据不足，且已见明显停顿** | 1050.25ms | 1734.54ms | 14 | accepted-load +55行，另有未关联的入口+1行 | [报告](/tmp/dsh-scroll-optimized-run1/summary.json) / [原始JSON](/tmp/dsh-scroll-optimized-run1/raw.json) / [CSV](/tmp/dsh-scroll-optimized-run1/raw.csv) / [context](/tmp/dsh-scroll-optimized-run1/context.json) |
| 阶段2 run1，首滑起采 | **有效 fail** | 270.11ms | 594.22ms | 18 | +62行 | [报告](/tmp/dsh-scroll-stage2-run1/summary.json) / [原始JSON](/tmp/dsh-scroll-stage2-run1/raw.json) / [CSV](/tmp/dsh-scroll-stage2-run1/raw.csv) / [context](/tmp/dsh-scroll-stage2-run1/context.json) |
| 阶段3 run1，首滑起采 | **有效 fail** | 58.42ms | 202.34ms | 3 | +62行 | [报告](/tmp/dsh-scroll-stage3-run1/summary.json) / [原始JSON](/tmp/dsh-scroll-stage3-run1/raw.json) / [CSV](/tmp/dsh-scroll-stage3-run1/raw.csv) / [context](/tmp/dsh-scroll-stage3-run1/context.json) |
| 阶段4 run1，首滑起采、ROW关闭 | **有效 fail** | 40.12ms | 283.36ms | 3 | +62行 | [报告](/tmp/dsh-scroll-stage4-run1/summary.json) / [原始JSON](/tmp/dsh-scroll-stage4-run1/raw.json) / [CSV](/tmp/dsh-scroll-stage4-run1/raw.csv) / [context](/tmp/dsh-scroll-stage4-run1/context.json) |

- 基线 run1 全活跃区间 P95 16.796ms、>33.3ms 占2.736%、20次 ≥100ms。
- 基线 run2 真实分页、完整分页 settle 与锚点0pt均成立；但最后 `scroll_end=80.866s`、末次回调81.766s、退出81.871s，返回转场使最后104.8ms活跃尾部未被回调覆盖，故严格判不足。原始报告保留，未修改门禁。
- 阶段1不足来自入口的+1行结构提交没有对应 accepted-load ordinal，不能把它算成已完成的受测分页。
- 阶段2增加相同的“最后一次手势后继续观察2秒”驱动条件，再返回触发导出；tail=0、无截断、锚点0pt、一个完整真实分页窗口。全活跃区间 P95 16.752ms、>33.3ms 占2.307%、19次 ≥100ms。prepare 阶段59个样本、P95 393.50ms、最大594.22ms、12次 ≥100ms，仍需优化。

- 阶段3同样没有截断或未完成页，锚点0pt、tail=0。全活跃区间 P95 16.762ms、>33.3ms 占2.441%、5次 ≥100ms；分页窗口 P95 58.416ms、>33.3ms 占14.510%、3次 ≥100ms。prepare阶段 P95 50.173ms、最大137.079ms、2次 ≥100ms，仍有残余停顿。

- 阶段4全活跃区间 P95 16.762ms、>33.3ms 占2.352%、5次 ≥100ms；分页窗口 P95 40.122ms、>33.3ms 占8.788%、3次 ≥100ms，最大283.356ms。prepare最大137.278ms、2次 ≥100ms，settle有1次 ≥100ms。ROW仪器默认关闭，正式raw的rowMeasurements为空。

最终成功方案须与采用同版仪器和手势条件的基线对照，并说明实际工作量。当前结果不能写成“已达60FPS”。

## schema3 首轮正式结果

两包均采用query驱动、30次真实手势、首滑前和末次手势后各观察2秒，ROW关闭；当时分析器SHA256相同。基线模式为`observational_coalescing`，仅被动观察原逻辑的实际准入和呈现；优化包为`owned_admission`，UI分页准入持续到最终呈现完成。以下保留原`legacy-arrival`报告的加载管线与settle合并统计，每个operation及每次commit仍分别接受门禁检查。

| 包与采样 | schema3门禁 | 分页P95 | 分页最大回调间隔 | 分页≥100ms | 实际前插 | 证据 |
|---|---|---:|---:|---:|---|---|
| 原始基线 schema3 run1 | **有效 fail** | 278.91ms | 1283.56ms | 13 | 3次commit，+56行 | [报告](/tmp/dsh-scroll-baseline-v3-run1/summary.json) / [原始JSON](/tmp/dsh-scroll-baseline-v3-run1/raw.json) / [CSV](/tmp/dsh-scroll-baseline-v3-run1/raw.csv) / [context](/tmp/dsh-scroll-baseline-v3-run1/context.json) |
| 阶段5 schema3 run1 | **有效 fail** | 32.47ms | 259.46ms | 3 | 3次commit，+39行 | [报告](/tmp/dsh-scroll-stage5-run1/summary.json) / [原始JSON](/tmp/dsh-scroll-stage5-run1/raw.json) / [CSV](/tmp/dsh-scroll-stage5-run1/raw.csv) / [context](/tmp/dsh-scroll-stage5-run1/context.json) |

用户批准新策略后，对同两组raw显式使用`--gate-policy display-cadence`另存报告；这是验收策略重算，不是新一轮采样。以下数值同样取分页管线与settle合并窗口，原先八组结果表的数值、原`summary.json`及raw均未改写。

| 原采样按display-cadence重算 | acceptanceGateStatus | 分页display P95 | 分页arrival ≥100ms | 分页display ≥100ms | 新策略报告 |
|---|---|---:|---:|---:|---|
| 原始基线 schema3 run1 | **fail** | 264.847ms | 13 | 14 | [另存报告](/tmp/dsh-scroll-baseline-v3-run1/summary-display-cadence.json) |
| 阶段5 schema3 run1 | **fail** | 17.870ms | 3 | 4 | [另存报告](/tmp/dsh-scroll-stage5-run1/summary-display-cadence.json) |

新报告的`acceptance_policy="display-cadence"`与`acceptanceGateStatus`共同表示当前验收策略及结果；`callbackGateStatus`始终保留`legacy-arrival`结论，两组也均为fail，不能将其误读为新策略成绩。每个窗口同时输出`arrival_statistics`和`display_timestamp_statistics`，保留全部到达抖动。新策略仍要求schema3完整生命周期、有效锚点、完整settle、无截断及真实滚动与pending重叠，未减免任何证据要求。[冻结清单](/tmp/dsh-scroll-display-policy-freeze.json)记录分析器/测试SHA及72项测试，[原文件校验](/tmp/dsh-scroll-display-policy-proof.json)证明旧raw和原summary的SHA保持不变；两组均无非正的可比较display间隔。

基线记录21次实际accepted，其中19次coalesced，归属2个完整operation；第一个operation包含+1、+7两次commit，第二个包含+48一次commit。阶段5记录3次accepted、0次coalesced、271次被拒调用，归属3个完整operation，分别前插+20、+16、+3行。两组每次commit锚点均有效、误差均为0pt，terminal、最新required epoch的真实issued/committed/presented及最终complete顺序可在raw复核；每个窗口均有滚动与pending重叠、完整settle，tail=0，均无样本、事件、诊断聚合截断或未完成生命周期。

全活跃区间方面，基线P95为17.175ms、>33.3ms占2.979%、13次≥100ms；阶段5P95为16.793ms、>33.3ms占2.149%、3次≥100ms。阶段5三个operation各自P95为32.467、33.341、16.776ms，全部仍超出16.7ms门槛。功能全绿与新准入生效均不能替代性能通过。

阶段5的3次≥100ms回调间隔为208.953、259.461、100.498ms，采样到达时phase均为`settle`，但这三个完整间隔分别跨越各次commit。raw中的commit begin→end自身耗时为74.158、172.123、50.086ms，不能把这些停顿全归为“commit后的settle”。回调phase描述到达时状态，不能据此拆分整个间隔的CPU归因；后续独立profile将继续定位提交及相邻工作的热点，诊断数据不混入正式成绩。

这两组不是严格等负载实验：[基线起始缓存](/tmp/dsh-scroll-baseline-v3-run1/start-cache.json)为400 events、seq4946–5345、554,583字符，[阶段5起始缓存](/tmp/dsh-scroll-stage5-run1/start-cache.json)为73 events、seq5273–5345、109,776字符；实际前插+56与+39行、accepted/coalesced数量也不同。这里只报告各自是否符合固定门槛，不计算精确speedup、改善百分比或推导所有会话的性能结论。

产物来源亦单独保留：[优化源码清单](/tmp/dsh-scroll-stage5-run1/source-manifest.json)与[基线测量清单](/tmp/dsh-scroll-baseline-20261020/measurement-only-manifest-schema3.json)分别匹配context内的清单SHA；两组`DshMobile`及`DshMobile.debug.dylib`的实际文件SHA均与各自context匹配，签名验证通过。基线另有[构建来源证明](/tmp/dsh-scroll-baseline-v3-run1/build-source-proof.json)，记录冻结源码、原始KMP框架、被动observer存在及优化准入/worker标记不存在。原始源码tar和旧schema2原始报告保持不变。

## 功能回归与诊断

| 验证 | 结果 | 证据 |
|---|---|---|
| 阶段1 iOS Markdown/投影/viewport/历史前插专项 | 35/35通过 | [测试日志](/tmp/dsh-scroll-ios-final-targeted.log) |
| 阶段1 Android Markdown绑定与历史恢复模拟器功能回归 | 9/9通过，不作性能证据 | [测试日志](/tmp/dsh-scroll-android-stage1-device-final.log) |
| 阶段2共享层全量 `iosSimulatorArm64Test` | 395项、35 suites、0失败/错误/跳过；Debug Framework链接成功 | [日志](/tmp/dsh-scroll-stage2-shared-validation.log) / [冻结XML](/tmp/dsh-scroll-stage2-shared-results) |
| 阶段2 iOS `GatewayProtocolTests`、`ChatHistoryCacheTests`、`MultiGatewayTests` | 146+19+10=175/175通过，含HistorySyncEngine相关回归 | [日志](/tmp/dsh-scroll-stage2-ios-regression.log) / [xcresult](/tmp/dsh-scroll-stage2-ios-regression.xcresult) |
| 阶段3共享层 / iOS回归 | 共享400项、36 suites全绿；iOS179项批中178通过，唯一源码审计误把`&&`当inout写入；精确修正scanner后，新scanner与两个ownership审计3/3复验通过，4项新worker功能测试原批均通过。重复项不叠加计数 | [共享日志](/tmp/dsh-scroll-stage3-shared-validation.log) / [共享XML](/tmp/dsh-scroll-stage3-shared-results) / [iOS首批](/tmp/dsh-scroll-stage3-ios-regression.log) / [审计复验](/tmp/dsh-scroll-stage3-audit-retry.log) |
| 阶段4共享层 / iOS回归 | 共享404项、37 suites、0失败/错误/跳过；iOS单批185/185通过（GatewayProtocol153、缓存19、多网关10、DEBUG行仪器3），覆盖前轮审计scanner修复 | [共享日志](/tmp/dsh-scroll-stage4-shared-validation.log) / [共享XML](/tmp/dsh-scroll-stage4-shared-results) / [iOS日志](/tmp/dsh-scroll-stage4-ios-regression.log) / [xcresult](/tmp/dsh-scroll-stage4-ios-regression.xcresult) |
| 共享修改后的Android兼容验证 | 单测211项、46 suites、0失败/错误/跳过；assembleDebug/lintDebug成功（0 error，40 warning、6 hint），APK apksigner verify通过；未操作模拟器数据 | [构建日志](/tmp/dsh-scroll-android-final-compatibility.log) / [汇总](/tmp/dsh-scroll-android-final-compatibility/summary.json) / [JUnit](/tmp/dsh-scroll-android-final-compatibility/unit-test-results) / [APK签名](/tmp/dsh-scroll-android-final-compatibility/apk-signature.txt) |
| 阶段5最终原生回归 | 签名Debug编译通过、当时schema3分析器57/57通过；初次新增fixture有异步等待/窗口初始化失败，修正后定点38/38，最终同产物完整单批221/221全绿（无重叠相加），未以功能通过代替性能验收 | [最终原生日志](/tmp/dsh-scroll-stage5-ios-final.log) / [xcresult](/tmp/dsh-scroll-stage5-ios-final.xcresult) / [当时分析器测试](/tmp/dsh-scroll-stage5-parser-tests.log) |
| 采样分析器边界测试（当前schema3） | 72/72通过，原57项全部保留；新增15项策略测试覆盖稳定display与arrival抖动、arrival/display长停顿、局部commit门禁、首nil、非首缺失/零间隔、legacy兼容及另存报告等边界 | [测试日志](/tmp/dsh-scroll-display-policy-tests.log) / [冻结清单](/tmp/dsh-scroll-display-policy-freeze.json) / [测试源码](/Users/lichaofan/DeepseekHarnessProject/dsh-mobile/scripts/test_summarize_ios_history_scroll.py) |
| 无行内代码时跳过自定义renderer | 新增3项及既有45项定点回归共48项通过、0跳过；12张旧新实窗截图人工复核未见中文、RTL、浅深色及代码背景可见回归，不替代性能验收 | [检查点](/tmp/dsh-scroll-inline-renderer-checkpoint) / [截图清单](/tmp/dsh-scroll-inline-renderer-checkpoint/fixture-attachments/manifest.json) |
| 无行内图片时移除任务/隔离图片状态 | 首轮新增5项为4通过、1项linked-image绿色断言失败；核对旧控制组后仅修fixture并增原能力对照，修后新增6项与既有48项共54项通过、0失败/跳过。不是与前轮重复叠加的总测试数 | [修后汇总](/tmp/dsh-scroll-noimage-checkpoint/regression-summary.json) / [新增6项日志](/tmp/dsh-scroll-noimage-checkpoint/new-image-fixed-tests.log) / [既有48项日志](/tmp/dsh-scroll-noimage-checkpoint/regression-fixed-tests.log) |

45秒、1ms间隔的 macOS `sample` 只对当前 iPhone Simulator 目标 App 的确切 PID 采调用栈，覆盖首滑之前至真实分页提交。它属于诊断，不能混入正式性能对照。

- [阶段1诊断](/tmp/dsh-scroll-sample-full-stage1/context.json)的主线程34,135个样本中，`applyHistoryRebased`约37.8%；按顶层去重的 JSON encode/decode 约32.7%；Hosted测高约2.05%。该证据支持第二轮优先消除多次全文跨桥序列化。
- [阶段2诊断](/tmp/dsh-scroll-sample-full-stage2/context.json)、[调用栈](/tmp/dsh-scroll-sample-full-stage2/sample.txt)、[对应分页原始事件](/tmp/dsh-scroll-sample-full-stage2/raw.json)已保存，供下一轮选最小修复。此次持久 OSLog 导出为空，不能声称从这些空日志取得分阶段CPU比例。
- [阶段3诊断context](/tmp/dsh-scroll-sample-full-stage3/context.json)、[调用栈](/tmp/dsh-scroll-sample-full-stage3/sample.txt)、[真实分页raw](/tmp/dsh-scroll-sample-full-stage3/raw.json)同组保存。改用Simulator实时日志白名单后捕获[同PID59209的26个匿名worker标记](/tmp/dsh-scroll-sample-full-stage3/worker-markers.json)，证明后台静态路径实际启用：中间失效结果按代次丢弃，最终5341 events准备与提交完成，总耗时432.36ms（含后台运行及等待，不能解释为MainActor占用时长）。该组属于额外诊断，不能混入阶段3正式成绩。

阶段4另有[ROW独立诊断](/tmp/dsh-scroll-stage4-row-diagnostic/context.json)：显式开启`DSH_HISTORY_ROW_METRICS=1`，同30次真实手势，154条逐行计时、`rowMeasurementLimitReached=false`、26个同PID匿名worker事件。此诊断与正式ROW关闭的成绩分离；`total`包含完整单行prewarm，`host_create`和`host_size`是子段，不可相加冒充总耗时。

另有[坐标手势对照诊断](/tmp/dsh-scroll-coordinates-stage4-diagnostic/context.json)：主App stub与debug.dylib的SHA与阶段4正式包完全一致，ROW关闭；公共`XCUICoordinate`缓存App根坐标，固定30次从视口25%至80%高度的拖动，循环不查询viewport；真实前插+62行。45秒`sample`仅用于检验XCTest重复AX snapshot干扰，不作正式性能通过。采用新driver时，最终基线与优化包必须都使用同条件重测。[同方法AX分析](/tmp/dsh-scroll-coordinates-stage4-diagnostic/ax-snapshot-analysis.json)显示：stage3 query的Main内topmost snapshot函数1249/37212=3.36%，stage4 coordinates为2940/36674=8.02%；两个生产阶段不同，不计算精确速度比，但该对照没有证明坐标API降低AX干扰。query仍是默认驱动。

[原生CUA能力探测](/tmp/dsh-scroll-native-stage4-diagnostic/context.json)也已保留作废结论：只选择iPhone17窗口，5次文档支持的drag及激活窗口后1次scroll均未观察到列表offset变化，自启动后没有新metrics报告。虽然对应45秒栈没有XCTest snapshot，但未形成UIKit滚动/分页，不能当作解决测量干扰的证据；未继续长驱动或私有输入注入。

## 阶段6候选：未命中并撤回

独立[commit分段诊断](/tmp/dsh-scroll-commit-diagnostic-run/commit-span-analysis.json)记录了diffable调用返回、异步completion进入、flush、锚点恢复及host创建/重配计数。异步等待时长不是CPU占用；`host_reconfigure`也不能直接证明同内容被重复设置。因此曾局部尝试仅对显式完成态、无图片/文件/knownFiles的assistant按完整渲染输入复用root，其它行保持原路径。

候选的[定点功能回归](/tmp/dsh-scroll-stage6-ios-regression.log)为43/43通过，但随后[真实命中诊断](/tmp/dsh-scroll-stage6-reuse-diagnostic/reuse-hit-analysis.json)的4个正数前插commit及各自1秒尾窗，`root_reuse`全部为0；[原始标记](/tmp/dsh-scroll-stage6-reuse-diagnostic/commit-markers.json)、[context](/tmp/dsh-scroll-stage6-reuse-diagnostic/context.json)与[源码清单](/tmp/dsh-scroll-stage6-reuse-diagnostic/source-manifest.json)保留用于复核。既有changed/revision筛选本已避免同key主动重配，本次观察到的host重配不能当作该候选消除冗余的证据。43项功能通过不构成优化有效或性能通过。

阶段6未执行正式性能采样，不计入正式结果表，也不计算收益。候选的生产代码、4项专属测试及`root_reuse`诊断字段已局部撤回；[三份回撤补丁和回撤前快照](/tmp/dsh-stage6-local-revert)可核。阶段1–5改造、原有commit分段诊断及其2项测试、独立空闲CADisplayLink校准测试均保留。回撤后的[定点回归39/39](/tmp/dsh-scroll-postwithdrawal-regression.log)与[签名验证](/tmp/dsh-scroll-postwithdrawal-signature.log)已通过，后续实际性能结果仍须另行记录。

新的warm-host实验与已撤回的同key root复用候选分开：它在显式DEBUG开关下，将同一Entry的预热host从控制器局部、最多4项的池中一次性转移给cell，严格校验token和环境，不按业务ID猜测等价。当前使用完整owner环境与实际测量render环境分别校验的候选已见真实命中，但尚未取得正式性能通过结果，不能写成优化有效或聊天性能达标。

### warm-host双环境短测：命中但仍失败

2026-10-21的[短测context](/tmp/dsh-scroll-warm-host-dualtraits-smoke/context.json)明确标记`diagnosticOnly=true`、`formalAcceptanceRun=false`：query驱动8次真实手势，warm和commit分段日志开启、ROW关闭，因此不混入上方正式对照表，也不据此计算收益。独立从[raw](/tmp/dsh-scroll-warm-host-dualtraits-smoke/raw.json)按当前冻结分析器重算，与[display-cadence报告](/tmp/dsh-scroll-warm-host-dualtraits-smoke/summary-display-cadence.json)逐字段一致，raw/CSV的SHA与context一致。此次1次实际准入、1个完整operation、1次commit前插20行；锚点误差0pt、tail=0，107个滚动与pending重叠样本，完整settle且无截断，仪器判定有效，但`acceptanceGateStatus=fail`，旧`callbackGateStatus`也为fail。

| 诊断窗口 | display P95 | display >33.3ms比例 | display ≥100ms | arrival ≥100ms |
|---|---:|---:|---:|---:|
| 分页管线与settle | 52.364ms | 7.602% | 3 | 1 |
| commit结束后完整1秒（含与边界相交的间隔） | 20.969ms | 3.704% | 2 | 1 |

[实际命中计数](/tmp/dsh-scroll-warm-host-dualtraits-smoke/warm-hit-analysis.json)中，首滑前累计`warm_host_hit=2`、最终为6，首滑至退出净增4；同期miss净增9、eviction净增10，池峰值4，fallback和环境不匹配均为0。正数commit完成时的累计hit仍为2，净增4来自其后的观测期；因此真实转移已经成立，但不能据此声称已消除本次commit开销或证明总体加速。

失败同时涉及准备、提交及提交后的窗口。raw中的prepare为3.840464–5.265938秒，按到达phase分组的prepare display P95为66.676ms、最大139.736ms，含1次≥100ms；commit为5.266169–5.345486秒，自身79.317ms。到达phase为settle的首个99.991ms arrival间隔覆盖5.248943–5.348934秒，横跨准备末段和整个commit，不能因commit桶没有回调就称提交无停顿。下一116.680ms arrival间隔位于5.348934–5.465614秒，完整落在commit结束之后。display间隔与arrival到达时钟分别保留，phase仅描述采样到达状态，不据此将整段display时间归为某个CPU函数。

[commit分段标记](/tmp/dsh-scroll-warm-host-dualtraits-smoke/commit-markers.json)显示该次diffable同步调用返回1.751ms、begin至completion进入74.507ms、completion flush4.022ms、anchor layout0.368ms、offset0.139ms；异步等待不是CPU占用。下一步先结合独立ROW诊断核对单行预热成本：现有3ms预算只在行与行之间检查，不能中断一行内部的host构造或测量。尚不根据这些elapsed值修改diffable行为，也不将旧系统中`animatingDifferences=false`的行为解释套用于当前iOS版本。

随后同签名包、8次手势的[独立ROW诊断](/tmp/dsh-scroll-warm-host-row-diagnostic/row-commit-overlap-analysis.json)记录20个历史准备`total`行scope，无截断；14个hosted行最大42.151ms，另6个user行最大0.520ms。独立核对[raw](/tmp/dsh-scroll-warm-host-row-diagnostic/raw.json)，唯一201.648ms arrival间隔为4.265005–4.466653秒，覆盖prepare末段16.669ms、prepare结束至commit开始约0.200ms、commit72.806ms及commit后111.973ms；与其中行scope实际相交的时间仅16.260ms，不能将整个回调间隔归因于单行预热。warm路径没有`host_create`/`host_size`子段计时，现有行scope也未单独覆盖提交后冷host创建/布局及已有行高度修正，因此不能把未覆盖时间解释为没有开销。所有scope均为墙钟elapsed，非CPU采样；此组开ROW的诊断仍不作为正式性能成绩。

### 无行内代码的默认绘制：功能通过，短测仍失败

候选仅在InlineText初始化时递归检测`.code`；没有code的段落使用原先构造的Text默认绘制，含code的段落保留原renderer，块级代码、主题、图片任务及文本属性均保持原路径。48项定点原生回归及12张synthetic截图支持本组布局与可见样式未回归，不代表任意内容像素完全一致，也未据此宣称链接点击或选区手势已测。

随后[WARM关闭、COMMIT/ROW开启的8次手势诊断](/tmp/dsh-scroll-inline-renderer-smoke/context.json)仍为有效fail，独立raw重算与[新策略报告](/tmp/dsh-scroll-inline-renderer-smoke/summary-display-cadence.json)一致：分页124个可比较样本的display P95为17.7225ms、>33.3ms为4/124（3.226%）、最大154.374ms；arrival最大137.740ms，display和arrival各有2次≥100ms。此组开诊断、工作负载与旧轮次不同，不纳入原正式表，不计算精确加速比例。

### MarkdownUI局部Debug优化：短测失败并撤回

2026-10-21曾独立尝试仅将vendored MarkdownUI的Debug编译设置改为`-O -assert-config Debug`。[构建检查点](/tmp/dsh-scroll-markdown-debug-o-checkpoint)保留实际参数、严格签名及48项native回归证据：主App仍为`-Onone`，MarkdownUI的`-Onone -O`以末尾`-O`生效，且保留`DEBUG`、调试符号及Debug assertion配置；Release与其它target未新增设置。12张synthetic旧新对照截图亦人工复核了中文、RTL、浅深色及行内代码背景，未见可见回归，但不能替代真实滚动验收。

[独立8次真实手势短测](/tmp/dsh-scroll-markdown-debug-o-smoke/context.json)采用WARM关闭、COMMIT/ROW开启，属于诊断而非正式采样。从[raw](/tmp/dsh-scroll-markdown-debug-o-smoke/raw.json)重算与[display-cadence报告](/tmp/dsh-scroll-markdown-debug-o-smoke/summary-display-cadence.json)逐字段一致：1个完整operation前插20行、锚点0pt、无截断；分页窗口124个可比较样本的display P95虽为16.667ms，但超过33.3ms的有4/124（3.226%），display最大166.356ms且有2次≥100ms，arrival最大161.959ms且有1次≥100ms，故新旧策略均为有效fail。20个`total`行scope最大43.669ms，仍不能把跨prepare/commit/settle的整段回调停顿归为单行或某一函数CPU耗时。

此次未取得足以保留局部编译优化的可靠收益证据，已精确撤回Package设置，恢复原manifest SHA256 `e14ae70c76c2ede1e5af86403c8356c1d494cbaeb0a9902c437653c4728ae692`；no-code renderer改动保留，未捆绑回撤。新构建仍须证明恢复后的实际参数，不能拿前一优化产物冒充已恢复产物。调试单步/局部变量及unsafeFlags的代价与撤回说明保留在[独立记录](/Users/lichaofan/DeepseekHarnessProject/dsh-mobile/Docs/MarkdownUI-Debug局部优化.md)。不同实验开关、起始缓存和实际内容负载不支持精确收益百分比，原正式表与旧报告均保持不变。

### 无行内图片任务快路径：修正fixture后功能通过，短测仍失败

该候选保留原loader按根`imageData`识别图片的能力：无可加载图片时不安装task或图片State；含图子视图独占状态，结果绑定确切inlines并在异步返回后拒绝取消任务的迟到结果。A切换至B加载期间不展示旧A图片。相对URL、alt、同输入不重启等原task合同保持，未顺带扩展嵌套图片支持。

首次测试把单子link包裹图片直接交给InlineText，错误期待绿色图片可见。源码及新增的改前原task/loader/Text控制组证明：该输入旧版会发起图片请求，但link走AttributedString路径，不消费已加载图片字典；原、新两路均不绘出该图片。测试据此将相对URL/alt/同输入实绘项改为直接image，仍严格要求绿色像素；linked输入另以旧新provider参数与无图结果一致为对照。其它迟到A→无图→B、同source不同alt、取消/失败后恢复的红绿实绘断言未放宽，也没有修改生产代码去扩展linked-image能力。修后6项新增与48项既有回归共54项全绿；[简单无图fixture附件](/tmp/dsh-scroll-noimage-checkpoint/image-fixture-attachments/4593D0BD-926C-41DE-B77D-43FD38A8E942.json)记录首次、两次yield及两次实际显示tick的高度均为53pt、provider请求为0，仅适用于该fixture，不能外推真实会话32pt修正的原因。

[随后8次真实手势短测](/tmp/dsh-scroll-noimage-smoke/context.json)已使用恢复后的主App与MarkdownUI `-Onone`，WARM关闭、COMMIT/ROW开启，仍属独立诊断。从[raw](/tmp/dsh-scroll-noimage-smoke/raw.json)重算与[display-cadence报告](/tmp/dsh-scroll-noimage-smoke/summary-display-cadence.json)一致：1个完整operation前插20行，锚点0pt、无截断；分页121个可比较样本的display P95为28.962ms，>33.3ms为6/121（4.959%），最大113.521ms且有1次≥100ms。arrival最大94.630ms、无≥100ms，仍不能称消灭长停顿；新旧策略均为有效fail。[ROW及commit诊断](/tmp/dsh-scroll-noimage-smoke/row-commit-overlap-analysis.json)中hosted行`total`最大46.230ms、host_size最大45.027ms，commit自身48.722ms，不能将整个相邻回调归为单一函数CPU开销。以上候选均未形成正式性能通过结果，继续定位；不同负载不支持精确收益百分比。

唯一113.521ms display间隔的采样到达phase为prepare；该样本对应的arrival间隔为52.816ms，其中行scope相交48.068ms。[关联分析](/tmp/dsh-scroll-noimage-smoke/display-stall-associated-arrival-analysis.json)明确这些相交值属于arrival时窗；raw没有绝对display timestamp，不能将该arrival窗当成完整113.521ms display时窗或据此进行精确CPU归因。

## 滚动优先调度：实现已完成，真实体验待测

2026-10-21按用户最新策略，将“已显示内容的滚动”与“新历史内容的准备、呈现”分开调度。tracking、拖动或减速期间保留现有展示snapshot，只合并最新待呈现target；领域层仍可继续接收和保存数据，UI分批不产生额外分页请求。冻结发生在`makeEntries`之前，缓存全命中也不能绕过准入直接提交。

连续停稳约120ms后，display tick入口恢复工作：每tick最多准备一条展示行，常规prepend每批最多新增两条展示行，从最接近已显示内容的一侧逐批补入。旧行revision或cell类型变化可以另需多次单行准备，不把它们称为新增消息；页头等supplemental新增也计入两行总预算。准备与提交错开tick，异步completion后下一tick才续批。真正删除、重排或替换走显式replacement并公开完整规模，不冒称有界prepend。单行同步测高仍不可抢占，分批本身不是性能达标证明。

中间partial批不确认历史呈现；完整target实际提交、锚点处理完成后，才确认对应历史epoch。领域terminal与最新required epoch实际呈现同时满足，owner才能释放本次准入。持续实时消息若沿用同一history epoch，不取消已冻结的准备批，也不让历史确认无限等待；更高history epoch到来则拒绝旧版本确认并重规划。无diffable的完全未变或轻量更新路径同样完成实际确认，避免缓存命中后gate一直busy。

如果提交已进入UIKit后才发生新拖动，异步completion只登记一次待收尾任务，保留忙态；再次连续idle后先收尾，下一tick才处理新批。用户已移动阅读位置时，在收尾前采集当前阅读锚点，纠高和恢复后再实测误差，不拉回提交前的位置，也不直接填零误差。session、宽度、字体及旧回调均受身份或环境检查。回到底部按钮的出现/消失动画收窄至按钮Group，保留scale与opacity效果，解除整个聊天页面的隐式动画作用域。

本轮4项调度规划、6项真实UIKit容器控制测试、54项既有定点回归及2项AppStore集成，共66项原生测试通过；[10+54项唯一测试汇总](/tmp/dsh-scroll-controlled-checkpoint/regression-summary.json)及[两项集成日志](/tmp/dsh-scroll-controlled-checkpoint/admission-integration-tests.log)保留。竞态fixture明确持有真实diffable completion，随后开始新拖动并改变实际offset，验证movement期间不回拉且无ack、idle后只确认一次；另覆盖cache-hit冻结、partial与更高epoch、持续live、宽度与空target、session切换。这里是功能证据，不预测真实滚动帧率；受控真实会话结果另列如下。

新体验报告使用[滚动优先调度测量合同](/Users/lichaofan/DeepseekHarnessProject/dsh-mobile/Docs/iOS滚动优先调度测量合同.md)及显式`--experience-policy interaction-priority`，读取独立`experienceGateStatus`；原`callbackGateStatus`与`acceptanceGateStatus`、历史raw和旧结果均保留。movement按完整真实时间区间相交归桶，检查display 16.7ms/1%与双时钟100ms；全活跃arrival 100ms仍硬失败，idle display长间隔及全部等待年龄完整公开，不能据新字段通过声称GPU 60FPS或所有等待均可接受。分析器115项Python边界测试与22组旧字段兼容重算属于测量验证，不替代真实会话验收。

### 受控真实会话第一轮：滚动区间达门槛，整体仍失败

2026-10-21的[本轮context](/tmp/dsh-scroll-controlled-presentation-smoke1/context.json)采用独立公开XCTest坐标驱动：2个cycle，每轮慢拖动并末尾hold 0.25s、快速释放、意图idle 0.20s、再次拖动尝试打断，最终提供8s完成机会。实际coverage以[raw](/tmp/dsh-scroll-controlled-presentation-smoke1/raw.json)为准，不将驱动意图当作UIKit事件。采样为标准签名Debug Simulator包，主App与MarkdownUI仍为`-Onone`；[构建参数](/tmp/dsh-scroll-controlled-checkpoint/build-proof.json)、[严格签名](/tmp/dsh-scroll-controlled-checkpoint/signature.log)、[源码清单](/tmp/dsh-scroll-controlled-checkpoint/source-manifest.json)及[安装二进制与raw校验](/tmp/dsh-scroll-controlled-presentation-smoke1/capture-proof.json)均保留。本轮WARM关闭，COMMIT与ROW诊断开启，作为新策略真实场景诊断，不混入旧正式对照表或计算精确加速比例。

独立从原始序列重新建立6个实际movement窗口，以完整双时钟区间相交归桶，与冻结分析器的[独立体验报告](/tmp/dsh-scroll-controlled-presentation-smoke1/summary-interaction-priority.json)一致。仪器有效、生命周期完整、尾窗覆盖且无截断，movement准入违规为0；`experienceGateStatus=fail`的唯一失败项是全活跃arrival存在≥100ms。原`callbackGateStatus`与`acceptanceGateStatus`亦均为fail，未回写旧报告或改变门槛。

| 完整区间统计 | 样本数 | P95 | >33.3ms | 最大间隔 | ≥100ms |
|---|---:|---:|---:|---:|---:|
| movement display | 805 | 16.667ms | 4/805（0.497%） | 64.923ms | 0 |
| movement arrival | 808 | 16.812ms | 3/808（0.371%） | 55.386ms | 0 |
| idle display（保留观察） | 163 | 59.498ms | 28/163（17.178%） | 117.175ms | 3 |
| idle arrival | 161 | 67.489ms | 26/161（16.149%） | 137.686ms | 2 |

因此本轮滚动区间的display 16.7ms/1%及movement双时钟无100ms门槛已满足，但整体体验仍未通过。idle display的3次长间隔按新合同完整公开、作为观察；idle arrival的两次137.686ms与100.026ms触发全活跃硬失败。arrival与display使用各自真实端点，不能把显示时钟间隔当作同长主线程CPU或GPU停顿。

本次只有1次实际历史准入、无coalesced准入，完整operation内20条新增正文展示行分成10批，每批恰好2条，未走replacement；历史准入之前另有1次无关联operation、无新增的in-place准入，不能混称第11个正数展示批。20次单行准备起止均配对；该operation期间记录4次实际`interrupted`，最终只确认最新required epoch4。各批锚点误差在浮点精度下约0pt，真实正数commit与最终ack均有原schema3证据，未用中间批释放owner。首正文呈现距离accepted为1.878s，最终呈现为13.336s；后者包含持续手势和中断等待，完整保留，不将停稳后工作时间或总等待隐藏。

[停顿时窗分析](/tmp/dsh-scroll-controlled-presentation-smoke1/idle-stall-scope-analysis.json)显示，137.686ms arrival窗为5.164959–5.302645秒，其中commit覆盖77.478ms、commit后仍有59.944ms；100.026ms窗为10.381654–10.481680秒，其中commit覆盖59.490ms、之后仍有38.481ms。两窗与已记录的准备行scope相交均为0，但这些scope不覆盖所有可见host创建、布局和后续修正，不能据此声称后段无工作或将整段停顿归因于某个函数；异步commit elapsed也不是CPU占用。

### 同二进制WARM诊断：历史阶段仅两次命中，仍失败

随后仅对[同一安装二进制](/tmp/dsh-scroll-controlled-warm-smoke1/capture-proof.json)开启WARM，其Debug dylib SHA与上一轮一致。[本轮raw](/tmp/dsh-scroll-controlled-warm-smoke1/raw.json)独立重算与[体验报告](/tmp/dsh-scroll-controlled-warm-smoke1/summary-interaction-priority.json)完全一致，证据有效，仍为`experienceGateStatus=fail`，唯一失败项仍是全活跃arrival≥100ms。movement display P95为16.667ms、>33.3ms为5/801（0.624%）、最大74.192ms，movement双时钟均无100ms；idle arrival则有4次≥100ms，最大121.177ms。idle display有3次≥100ms、最大124.797ms，继续完整保留观察。旧两个Gate也均失败，未据某个最大值下降计算收益。

[匿名累计标记](/tmp/dsh-scroll-controlled-warm-smoke1/anonymous-app-markers.json)显示首滑前已有3次`warm_host_hit`，前两个历史批完成时累计分别为4、5，直到最终仍为5：本次历史阶段净增只有2次，不能把累计5次都当历史命中，也不能泛称10批均复用了host。最终miss为25、eviction为8，池峰值4，transfer fallback及environment mismatch为0。[批次与行scope关联](/tmp/dsh-scroll-controlled-warm-smoke1/warm-batch-row-correlation.json)公开每批测量类型及20条准备行，所有批的旧行changed计数为0；这些匿名元数据不能再推断hosted行正文类别或未覆盖的可见布局成本。

同样的1个operation前插20行、10批各2条，4次active operation中断，最终epoch4完整确认，锚点误差约0pt；首正文延迟1.972s，最终13.550s，均保留连续手势等待。[4次arrival长间隔的scope分析](/tmp/dsh-scroll-controlled-warm-smoke1/idle-stall-scope-analysis.json)中，3次分别覆盖commit及其后段，另一次104.489ms没有commit相交，仅22.076ms与一个准备行scope相交；不能把任一完整长间隔全部归因于预热、commit或单个CPU函数。此次无可靠通过证据，WARM仍为默认关闭的诊断实验，不提升为正式优化结论。

下一轮最小候选仅将controller常规prepend的实际新增批次由2收紧为1；通用规划器及验收合同仍保留≤2上限，分析器与全部旧报告不变。需用实际snapshot新增量、完整锚点与最终ack的原生测试和新真实采样判断，尚不预测性能通过。

该候选新增的actual snapshot原生测试首轮出现锚点失败。[synthetic几何附件](/tmp/dsh-scroll-controlled-cap1-checkpoint/anchor-observation-attachments/33091654-A3A4-4257-81FF-CFD68C36355D.json)记录：初始位于bottom offset326，测试仅程序化设为180，未进入用户阅读状态；首批完成恢复anchor offset0，第二批后却回到新bottom406，anchor offset为−146，与初始尾部跟随/对齐状态未取消一致。不能将这次失败写成生产逻辑已修复。

修正仅限测试前置：调用真实拖动delegate进入阅读、设offset180、结束手势，等scheduler及inflight均清空后采一次固定基准，再开始分页测试。此后每个实际diffable snapshot新增≤1、每批相同阅读锚点误差≤1pt、chrome加两条缓存正文的3批、再加两条长Markdown的总5批及epoch1/2最终确认断言均保留，没有逐批重设基准、跳过或放宽断言。生产Viewport仍为`d64a5cb11e4aa2616355898670e4e5dec535ad162c1c16b7cba7d89b24608cc3`，测试修正版为`a3c7d8135a5cde1eb367821a3176fb609cdb438b5890fa7ba5267696beb2f953`。[最终回归汇总](/tmp/dsh-scroll-controlled-cap1-checkpoint/regression-summary.json)保留首轮66通过/1失败、修后单项通过及随后独立一批67/67唯一通过、0skip，不以拼接重复用例替代整批通过。

### 每批一行真实诊断：完整呈现，仍有一次idle回调长停顿

[cap1真实raw](/tmp/dsh-scroll-controlled-cap1-smoke1/raw.json)独立重算与[体验报告](/tmp/dsh-scroll-controlled-cap1-smoke1/summary-interaction-priority.json)一致，证据完整、无截断、movement准入违规为0；[构建与安装校验](/tmp/dsh-scroll-controlled-cap1-smoke1/capture-proof.json)关联本轮签名Debug、源码及二进制。1个operation将20条新增正文分成20个实际正数commit，每批恰好1条；4次active operation中断，20个锚点误差均在浮点精度下约0pt，最终完整确认epoch4，未用部分批次提前释放owner。

movement display共803个可比较间隔，P95为16.667ms、>33.3ms为5/803（0.623%）、最大70.348ms，movement arrival和display均无≥100ms。本轮滚动区间门槛仍满足，但`experienceGateStatus=fail`：唯一失败项是全活跃arrival中一次127.426ms的idle长间隔。旧whole-pipeline两Gate同样失败。首正文呈现延迟1.891s，最终13.450s，包含持续手势与等待，均完整保留；不能只据滚动桶达标宣称整体完成，也不从相邻诊断轮次计算精确收益。

[唯一长停顿分析](/tmp/dsh-scroll-controlled-cap1-smoke1/idle-stall-scope-analysis.json)中，arrival窗13.165375–13.292801秒不与movement相交，包含commit59.510ms及其后67.317ms；没有准备行scope与此窗相交。该批对应的一条19.023ms准备行早在10.432636–10.451659秒完成，之后被用户手势暂缓，约2.714秒后才进入本次commit，不能将先前准备耗时加到127.426ms内。同期display区间为另一个113.359ms时窗，两时钟仍分开解释。commit elapsed及后续未细分时间均不能直接归为某个函数CPU成本。

下一候选仅评估普通历史prepend期间的ChatLayout批更新补偿，尝试在UIKit提交阶段维持旧可见内容的相对位置；这不是永久自动滚底，也未证明能减少可见host工作。须保持手动锚点恢复、完整事务退出及新手势/会话隔离，并先通过原生与真实验证；验收合同、分析器与已有结果继续保留。

## 调度参考：host空闲与独立UIKit

2026-10-21，两项参考均使用相同模拟器、默认CADisplayLink刷新策略、主线程`.common`模式及`CACurrentMediaTime()`，固定稳定2秒后连续采集约25秒原始间隔。它们用于辨别测量环境，不包含真实聊天滚动和分页验收。

| 参考 | 有效interval数 / 时长 | arrival P95 / 最大值 | display timestamp P95 / 最大值 | arrival >33.3ms / ≥100ms | 证据 |
|---|---|---|---|---|---|
| 原App的XCTest host空窗口 | 1498 / 25.002s | 19.482ms / 33.266ms | 16.667ms / 33.333ms | 0 / 0 | [汇总](/tmp/dsh-scroll-idle-calibration/summary.json) / [原始序列](/tmp/dsh-scroll-idle-calibration/raw.json) / [CSV](/tmp/dsh-scroll-idle-calibration/raw.csv) / [context](/tmp/dsh-scroll-idle-calibration/context.json) |
| 独立纯UIKit Debug进程 | 1501 / 25.017s | 16.733ms / 17.250ms | 16.667ms / 16.667ms | 0 / 0 | [汇总](/tmp/dsh-standalone-cadence-probe/run1/cadence-summary.json) / [原始序列](/tmp/dsh-standalone-cadence-probe/run1/cadence-raw.json) / [CSV](/tmp/dsh-standalone-cadence-probe/run1/cadence-raw.csv) / [context](/tmp/dsh-standalone-cadence-probe/run1/context.json) |

host空窗口校准通过的是1/1采样完整性检查，仍包含XCTest与原App可能的后台工作，不能称纯OS底噪。独立UIKit probe使用新bundle `com.clark.dshcadenceprobe.debug` 的纯色窗口，未链接产品、SwiftUI、KMP、store或XCTest；采样期间无AX查询、手势、Profiler或并发构建，且不读写真实App数据。Simulator、宿主系统及其它进程的调度仍存在，因此后者也只是独立进程的调度参考，不能当作整个系统的隔离实验。

两者的P95均按nearest-rank从原序列计算，arrival描述实际回调到达间隔，display timestamp描述显示时钟间隔，两套统计分别保留。两项都不是GPU/rendered FPS，不证明聊天应用已满足门槛。用户已明确批准以display-cadence验收聊天滚动：P95与>33.3ms比例取可比较display间隔，arrival及display的≥100ms仍各自为硬门禁。**16.7ms、1%、无≥100ms停顿及1pt锚点阈值数值未改**；调度参考不能替代真实聊天加载证据，现有两组正式raw按新策略仍均失败。

独立probe的[冻结源码说明](/tmp/dsh-standalone-cadence-probe/README.md)有一处表示层勘误：首个nil interval在JSON中由默认JSONEncoder省略对应可选字段，CSV中为空；README所写的“null”应按此理解，未填入正常帧数据。冻结源码和源SHA保持不变，无需为此修改原序列或重跑。

## 复现与产物校验

1. 保留已有真实配对与数据库，在已连接主页选择已结束且足够长的真实 Session。通过本机私有环境变量提供 deep link，不提交 URL、原始 ID、配对码或凭据。
2. 构建标准签名 Debug Simulator 包（不同基线/优化 DerivedData，不能覆盖原始基线源码）：

```sh
xcodebuild build-for-testing \
  -project DeepSeekHarnessMobile.xcodeproj -scheme WorkspaceDrawerUITests \
  -configuration Debug \
  -destination 'platform=iOS Simulator,id=BB56B073-C41F-4984-A3DA-54E14E71E1FB' \
  -derivedDataPath /tmp/dsh-scroll-measurement/SimulatorDerivedData \
  -only-testing:DeepSeekHarnessMobileUITests/RealSessionScrollPerformanceTests \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=-
codesign --verify --deep --strict /tmp/dsh-scroll-measurement/SimulatorDerivedData/Build/Products/Debug-iphonesimulator/DshMobile.app
```

3. 在同一个 Products 目录创建权限600的私有 `.xctestrun` 副本，注入 `DSH_REAL_SESSION_SCROLL=1`、`DSH_SCROLL_ALLOW_SIMULATOR=1`、`DSH_SCROLL_SWIPE_COUNT=30`、`DSH_SCROLL_SECONDS=120`、`DSH_SCROLL_EXISTING_SESSION_URL`。不要打印最后一项。驱动由正常 App launch/open 开始，不能使用 fake/reset 参数。
4. 只运行 `RealSessionScrollPerformanceTests/testScrollTowardOlderHistoryInExistingSession`，禁并行测试；结束后从当前 App data container 的 `Documents/DebugHistoryScrollMetrics` 复制该组 JSON/CSV。诊断环形只保留最近4组，必须及时归档。
5. 使用冻结分析器，显式选择当前批准策略，并另存报告：

```sh
python3 scripts/summarize_ios_history_scroll.py /path/to/raw.json \
  --gate-policy display-cadence --output /path/to/summary-display-cadence.json
python3 -m unittest discover -s scripts -p test_summarize_ios_history_scroll.py
```

省略`--gate-policy`时默认仍为`legacy-arrival`，用于重算旧口径；不要覆盖历史报告。当前验收读取`acceptance_policy`及`acceptanceGateStatus`，`callbackGateStatus`仅表示旧arrival口径；证据不足不能当作通过。

6. 保存同组 raw/CSV、各策略summary、策略参数及分析器SHA、xcresult、驱动日志、匿名起始缓存统计、源码清单及 `DshMobile` 和 **`DshMobile.debug.dylib`** 的SHA256。Xcode Debug主实现位于后者，不能仅对启动stub取hash。测试日志可能含私有URL，不直接向外展示整份日志。

原始基线冻结包[源码tar](/tmp/dsh-scroll-baseline-20261020/source.tar.gz)的SHA256为 `acc96752d280b58157bbef1e9d8a03a0d395541337b7fc132ee0b3101837446e`。测量用基线只叠加同版 DEBUG sampler、标记和手势驱动，不叠加性能优化；原tar始终保留。阶段2[源码清单](/tmp/dsh-scroll-stage2-run1/source-manifest.json)与[二进制context](/tmp/dsh-scroll-stage2-run1/context.json)可用于复核。
