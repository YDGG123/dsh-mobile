# Android 历史分页体验对齐

## 最新调整：按阅读窗口批量补页

2026-10-21。针对 Android 短消息及工具密集历史需要反复触边的问题，区分传输页与阅读批次。只改 Android；iOS、共享层与首屏请求参数不变。

原因是网络页原来最多60个事件、本地页80个事件，事件数包含工具调用、工具结果和思考；几十个事件折叠后可能只增加一个过程标题。现在较早历史的网络页最多120个事件、本地页160个事件；用户只需触发一次阅读批次，正文不足时后台继续补页。

阅读目标为约两屏新增用户/助手正文的估算高度，考虑当前视口、可用宽度、字体缩放、换行、图片和文件。工具、工具结果、思考与 Markdown 分块不按独立正文计数；工具事件仍完整保留，可在过程详情中查看。这是呈现层的估算目标，不是预先测量所有新行。首屏与已显示正文、实时新增尾部不计入本次补页目标。

连续补页期间保留当前显示快照，不增加列表滚动范围、不逐页插入。批次结束后沿用后台准备、原子锚定、松手后提交和新增历史渐显。切换会话取消该会话的呈现任务；补页出错则保留已收数据并允许再次触边加载。

自动补页在正文目标达到、历史结束、错误、游标不再前进、累计8个传输页、累计新增投影文本达到1,048,576字符或累计耗时达到2.5秒时停止。时间和数量预算在响应结束后判断，不承诺正在等待的网络请求在2.5秒内完成。达到预算时呈现已取得内容，后续仍可由用户再次触发。

新增日志 `read-batch-start`、`read-batch-continue`、`read-batch-ready`，只记录页数、正文数量、估算高度及停止原因，不记录聊天内容或连接凭证。本轮同时修正旧快照先于请求状态被消费、使批次提前结束的竞态。


本轮最终验证：225项单元测试、48个套件与61项Android 17模拟器设备测试全部通过，0失败、0跳过。设备回归包括17项分页专项、真实Room分页/离线主页、网络请求参数、1200个增量归并、图片及键盘等流程。短页专项验证连续两个单条页自动补取，再将整批新增正文统一呈现；纯工具页专项确认60个工具事件仍然保留，且会自动补取正文。补页时列表滚动范围保持不变，手指松开后保持正文位置。日志中4次有效阅读锚点误差均为0px。

lint无Error/Fatal，保留40项已有Warning。APK签名验证通过，交付包与模拟器实际安装包SHA256一致；357个iOS/共享源码文件相对本轮起点指纹未变。本轮属于模拟器功能、几何与Room/模拟网络集成验证，不代表真实网关延迟或GPU FPS测量。

扩展测试修复了旧夹具：离线页大小调整后使用400序号水位保留连续三页与边界断言；固定并恢复中文Locale；网络夹具明确历史格式版本及事件必填kind字段，并按当前“最终回复保持流式行ID”契约检查最终事件水位和唯一完整正文。未为这些测试修改共享层或降低断言。各失败轮次和诊断日志仍保存在本轮目录，不与最终通过结果合并。

- [本轮签名 Debug APK](/tmp/dsh-android-history-read-window/final-pass/DshMobile-Android-Debug.apk)
- [设备测试结果](/tmp/dsh-android-history-read-window/final-pass/device-summary.json)
- [设备完整日志](/tmp/dsh-android-history-read-window/final-pass/device-tests.log)
- [单元测试结果](/tmp/dsh-android-history-read-window/final-pass/unit-summary.json)
- [批次补取及阅读锚点日志](/tmp/dsh-android-history-read-window/final-pass/batch-and-anchor-diagnostics.json)
- [源码隔离、签名与安装包核验](/tmp/dsh-android-history-read-window/final-pass/isolation-and-package-proof.json)

## 上一轮记录：历史入口按需显示

本次根据用户截图反馈，仅调整 Android 的交互和提示样式，保留上一轮的后台准备、阅读锚点及新增历史渐显。iOS 与共享层源码不变。

进入会话、仅接近顶部、程序定位到顶部，都不显示入口或请求更早历史。到达窗口顶部后，用户继续向更早记录方向拖动超过8dp，才显示加载状态并请求一页；同一次触摸最多请求一页，惯性不能继续拉页。短缓存窗口即使不足一屏也支持这个操作。

入口使用12sp的中性次要文字“加载更早记录”，没有胶囊背景、阴影或强调色。加载时配合同色小型进度圈；请求失败后恢复可点击的文字，用户可点击或再次拖动重试。离开顶部后隐藏入口，返回顶部不会自动再次显示。没有更早记录时不显示“已到达最早记录”。顶部保留32dp稳定空间，提示显隐不插入列表行、不推动正文。

本轮最终验证：217项单元测试、45项Android 17模拟器设备测试全部通过，0失败、0跳过；包含12项历史分页专项。实际设备测试覆盖仅接近顶部的真实拖动、程序到顶、继续拖动、短窗口、点击重试、离开边缘再返回，以及上一轮的阅读位置与分组场景。4次有效锚点日志观测均为0px。lint无Error/Fatal，40项已有Warning。签名通过，交付包与实际安装包SHA256一致；357个iOS/共享源码文件指纹与本轮开始时完全一致。

- [本轮签名 Debug APK](/tmp/dsh-android-history-edge-intent/accepted/DshMobile-Android-Debug.apk)
- [本轮设备测试结果](/tmp/dsh-android-history-edge-intent/accepted/device-summary.json)
- [本轮设备测试完整日志](/tmp/dsh-android-history-edge-intent/accepted/device-tests.log)
- [本轮单元测试结果](/tmp/dsh-android-history-edge-intent/accepted/unit-summary.json)
- [本轮源码隔离与安装包校验](/tmp/dsh-android-history-edge-intent/accepted/isolation-and-package-proof.json)
- [本轮加载状态截图](/tmp/dsh-android-history-edge-intent/accepted/edge-loading.png)

本轮已替代上一轮“距顶部6项自动预取”和常驻悬浮胶囊的策略。以下上一轮记录及旧验证包仅作历史留档。

## 上一轮实现与验证记录

2026-10-21。本轮仅改 Android 原生呈现和适配代码。用户明确要求保留当前 iOS 表现，因此不迁移共享状态机、不修改 iOS、KMP 业务、Room schema 或分页游标规则。开始前记录的357个 iOS、iOS测试和共享源码文件，结束时内容指纹全部一致；原有未提交的图标、会话状态等工作保留。

## 问题与处理

此前 Android 把加载提示与正文快照一起暂停。滑动触发请求后，提示可能等到停止才出现；临时的列表内加载行还会增加、消失。当这个临时行或合并前的过程分组是首个可见 key 时，仅依赖 LazyColumn 的默认保位容易回到另一个窗口起点。

现在加载信息独立于正文冻结：靠近顶部可看到“加载更早记录”，请求开始立即显示加载状态，数据已到达但呈现尚未完成时显示准备状态。失败可重试，空页可正常收尾，历史耗尽显示边界提示。提示不再插入、删除列表项，首行预留固定空间，加载状态变化不会改变列表索引或挤压正文。

真实向较早消息的滚动在距顶部6个展示项内开始预取，保留程序滚动和重复请求保护，不等到碰到窗口边缘才开始请求。网络、Room和归并仍走已有共享业务实现；不宣称网络耗时被消除。

Android 的分组、Markdown 分块改在后台准备。滑动中继续准备历史及阅读窗口附近的 Markdown，保留一个候选快照；当前已显示内容正常滚动。停止触摸和惯性后等待120ms，再提交准备好的内容，不等待整页所有 Markdown 都预热。连续 live 更新仍使用最新候选，不把普通 token 更新加入180ms的动画等待。

提交前选择仍存在、内容未变的可见正文，记录其 key 和屏内像素偏移。数据替换与 `requestScrollToItem` 定位请求在同一可变快照中提交，使下一次测量直接使用正确位置，而不是先绘制错误窗口后再滚回来。旧过程头被合并时，优先锚定其后保留的正文；屏内只有过程组时按共有事件映射到新组。

新历史插入后，等待连续两次几何保持一致，再让新增内容以180ms透明度动画渐显；旧正文不参加动画。用户再次触摸会立即显示已插入内容，以用户新的阅读位置为准。手势代次参与调度目标，短暂触摸也能唤醒尚未呈现的候选，避免“准备中”一直不结束。只修改历史元数据、不产生新聊天行的页同样能收尾。

关键日志使用 `HistoryPresentation` 标签，记录 request-start、prepare-start、background-ready、prepare-ready、presented 阶段，以及准备耗时、插入项数、锚点有效性、误差和手势打断；不记录聊天正文、连接地址或凭证。

## 验证

- Android Debug构建成功，APK签名验证通过。
- 216项单元测试、47个测试套件全部通过，0失败、0跳过。
- Android 17模拟器40项设备回归全部通过，0失败、0跳过。覆盖7项新增历史分页测试、首屏恢复、Markdown与表格、图片预览、滚底、键盘/审批及真实Room缓存操作。
- 历史用例覆盖连续两页、手指未松开时收包、快速触摸、失败重试、空页、过程组合并、仅元数据变化和加载中切换会话。实际 Compose 屏内位置断言容差为1物理像素；最终日志中的4次有效锚点观察误差均为0px。
- lint无Error/Fatal，保留40项现有Warning；没有为这次改动降低检查规则。
- 交付APK与模拟器实际安装APK的SHA256一致。采用覆盖安装，没有使用卸载或清除应用数据命令。

扩展回归先暴露了旧测试的夹具问题：部分用例硬编码中文标签，却在英文进程中运行；另有两项语音用例仍假设“文字框长按录音”或“录音时文字框仍存在”，与当前独立语音模式不一致。测试固定并恢复进程Locale，语音夹具使用当前入口与实际语音输入控件，继续保留草稿、编辑、蒙层和系统导航区域颜色断言；未修改语音产品逻辑。各轮失败日志保留，没有将失败轮次合并为通过。

上述结果属于模拟器的功能、布局与Room集成验证，不是新一轮真实网关耗时对比或GPU FPS测量。

## 交付与证据

- [签名 Debug APK](/tmp/dsh-android-history-alignment/accepted/DshMobile-Android-Debug.apk)
- [单元测试汇总](/tmp/dsh-android-history-alignment/accepted/unit-summary.json)
- [设备测试汇总](/tmp/dsh-android-history-alignment/accepted/device-summary.json)
- [设备测试完整日志](/tmp/dsh-android-history-alignment/accepted/device-tests.log)
- [构建、单元测试与lint日志](/tmp/dsh-android-history-alignment/accepted/build-unit-lint.log)
- [锚点诊断](/tmp/dsh-android-history-alignment/accepted/anchor-diagnostics.json)
- [源码指纹](/tmp/dsh-android-history-alignment/accepted/source-manifest.json)
- [iOS/共享源码隔离与安装包校验](/tmp/dsh-android-history-alignment/accepted/isolation-and-package-proof.json)
