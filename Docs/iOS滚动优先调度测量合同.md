# iOS滚动优先调度测量合同

日期：2026-10-21。本文定义用户最新“已显示内容滚动优先，idle时分批呈现”的测量方案。分析器通过显式选项实现独立体验报告；产品调度、原生与真实会话验证另行记录，本文不代表性能通过。

旧raw、CSV、summary、阈值数值与旧策略实现均保留；历史整条分页管线的失败报告不改写。新报告分别展示movement与idle/commit体验，不能用某个新分桶通过替换旧报告，不能省略idle停顿或无限延期的代价。GPU/rendered/presentation FPS仍为`unsupported`。

## 时基与新增原始证据

原采样器的`startedAt`为当前`CACurrentMediaTime()`时基下的测量起点。新采样增加每个callback的：

```text
displayTimestampElapsedSeconds = displayLink.timestamp - startedAt
previousArrivalElapsedSeconds = previousArrival.map { $0 - startedAt }
```

必须使用`timestamp`，不能以未来的`targetTimestamp`、callback实际到达时间或累计“理想16.667ms”替代。以下既有字段继续原样采集：

- `elapsedSeconds`：callback实际到达相对同一startedAt的时间。
- `callbackArrivalIntervalMs`：既有arrival指标，切入active时按`max(previousArrival, activeSince)`裁切；旧政策仍原样使用，不将它重新命名为完整间隔。
- `displayTimestampIntervalMs`：相邻display timestamp差值；真正首callback没有前一条display证据，继续留空。
- `expectedDurationMs`、`isFirstCallback`、`activationClipped`、`isScrolling`、`isHistoryLoading`、phase及全部生命周期、限额字段。

新增display时间可以在首callback略小于0：display timestamp可能对应测量起点之前的显示更新。保留真实值，不钳成0、不平移该样本，也不据此伪造首个display interval。后续可比较间隔须有限、严格为正；其端点差值须与原display delta一致。负数、非有限数、缺失或矛盾的时间证据不能作通过处理，浮点容限应在分析器边界测试中明确冻结。

生命周期schema3的operation/epoch证据不因此改成另一套业务状态。新分桶报告须明确记录本合同版本（建议`movement-idle-v1`）和分析器SHA；旧raw缺少新display绝对端点时仍可按原策略解析，不得伪装成已具备新movement显示时钟证据。

## movement窗口

使用实际UIKit滚动事件建立有序窗口：

1. `scroll_begin`进入窗口。
2. 结束拖动且无减速时结束；有减速时直到减速结束才结束。
3. 持续的新手势/减速窗口相交时取并集；聚合统计中同一完整间隔只计一次。
4. 仅跟踪触摸但尚未触发拖动时，产品调度仍应冻结历史呈现。若要把tracking纳入测量窗口，须额外记录真实tracking起止，不能由最终callback的布尔标志反推开始时刻。
5. 未闭合movement窗口、缺失起止、旧session事件混入或采样尾部未覆盖，明确标记证据不足；不能通过提前关闭窗口剔除尾部停顿。

这些是App实际接收到的滚动事件窗口，不等于独立测得物理触摸到主线程处理之间的延迟。单个同步重行测高不可抢占，仍需结合边界间隔及调度事件检验；不能声称已测得GPU帧率或所有触摸延迟。

## 完整间隔分类：相交即整段计入

对每个arrival样本，新体验政策的完整间隔为：

```text
[previousArrivalElapsedSeconds, elapsedSeconds]
```

真正首callback没有previousArrival时，从测量起点0至该callback保留首滑真实等待；非首且`activationClipped=true`缺新增previousArrival端点时，新体验证据不足，不以已裁切原delta猜出完整间隔。非裁切样本可用原delta反推上一到达端点，有新增端点时优先使用并校验一致性。新增字段不改变旧raw的原delta或旧验收。

对每个可比较display样本，完整间隔为：

```text
[previousDisplayTimestampElapsedSeconds, displayTimestampElapsedSeconds]
```

上一个真实display端点由同显示时钟的`displayTimestampElapsedSeconds - displayTimestampIntervalMs / 1000`获得；连续存储样本时与上一条绝对display时刻交叉校验。必须使用实际display端点，不能将display delta倒减callback的arrival端点来冒充display时窗。

原helper在纯inactive时继续更新真实previous clocks，但不存储callback；因此相邻“存储样本”未必是相邻真实callback。新体验不把这段未采集空闲伪造成一个长callback；只允许明确`activationClipped`重入且间隙不覆盖gesture或operation→complete+1s保护窗口的情况，并公开`omitted_inactive_gaps`数量。pending/movement期间缺样本仍不足。首个存储sample若此前有真实未存储callback，可为`isFirstCallback=false`，须有明确previousArrival、有效display delta和裁切证据；真正首callback仍禁止伪造display delta。

任一完整间隔与任一movement窗口相交，就把**整个原始间隔**计入该时钟的movement统计。边界只接触端点也按保守的movement处理，并单列接触计数；绝不把长间隔裁成几个较短片段再计算P95或长停顿。完全不相交的完整间隔归入该时钟的idle统计。

arrival和display各自按自己的完整时窗分类，因此同一个callback的两套间隔可以归入不同桶。到达时`isScrolling`或phase只供旁证，不能决定整个间隔的归属。

例如，200ms间隔在减速结束后1ms才到达，但覆盖了减速窗口：完整200ms进入movement，不能把它只归idle或截成1ms。100ms间隔横跨120ms idle计时结束和commit，也保持100ms，不能因phase为settle而剔除。

每个时钟均必须公开：

- 全序列、movement、idle的样本数、nearest-rank P50/P95、最大值、>33.3ms数量与比例、≥100ms数量。
- 与movement窗口任意相交、跨越起止边界、仅端点接触的数量；同一间隔涉及多个窗口时的去重规则。
- 缺失/无效时间、未闭合窗口、采样或事件/诊断截断、未覆盖尾部的计数与状态。

新`interaction-priority`政策检查movement display P95≤16.7ms、>33.3ms比例≤1%，movement arrival与display各自均无≥100ms；此外**全活跃arrival≥100ms仍为硬失败**，不因发生在idle而删除。idle display≥100ms保留完整计数、最大值和边界归属，作为显示时钟观察，不单独等同主线程或GPU停顿而判硬失败。显示时间窗与arrival时间窗可以不同，不能把它们的耗时互相归因。

这是用户本次“滚动优先、停稳可短暂停”策略下的新滚动/调度验收，须在采集新结果之前冻结。旧`display-cadence`对全活跃arrival与display双≥100ms的硬门禁完全保留，仍可能与新体验判定不同。idle P95不另设16.7ms门槛；idle/commit最大耗时、pending年龄、首批与最终等待全部另报人工评估。新体验通过不能代表所有等待均可接受、全部体验完美或GPU 60FPS。

分析器运行示例（新文件名，不覆盖旧报告）：

```shell
python3 scripts/summarize_ios_history_scroll.py raw.json --gate-policy display-cadence --experience-policy interaction-priority --output summary-interaction-priority.json
```

`callbackGateStatus`始终为旧arrival口径；`acceptance_policy`与`acceptanceGateStatus`仍仅由原`--gate-policy`决定。新增`experience_policy`、`experienceGateStatus`和`experience`区段独立表达本合同，缺新绝对display时钟、gesture或scheduler诊断时为`insufficient-evidence`。不指定`--experience-policy`时不添加这些新字段，旧raw仍可按旧口径重算。两套旧政策的旧结果字段不被新体验结果覆盖。

## idle与commit信息保持完整

原来accepted→prepare→多次commit→terminal与最终呈现→settle的全生命周期保留。每次commit及其完整settle、anchor误差、真实正数前插、滚动与pending重叠、截断与未完成检查仍公开。中间展示批不是新的MVI请求，也不能虚增accepted次数。

新调度以连续idle约120ms为准入条件，但这不意味着把该120ms或commit从原始时钟序列删除。必须记录：

- 调度进入movement冻结、idle等待开始、等待被打断、idle准入、准备行开始/结束、展示批提交开始/结束、最终stamp实际确认。
- 每批实际新增**展示行**数，不称消息数；常规prepend每tick最多1行测高、每批最多2新增展示行。旧行revision变化可以另需多次单行准备，不要求prepare与commit一一对应；缓存全命中允许0次prepare，但commit仍须满足idle准入。
- prepare与commit是否分开tick、completion后是否留到下一tick续批；单行同步工作的真实耗时，不用总deadline把超时行隐藏。
- 期间的滚动/减速、宽度/字体/环境变化、session切换与取消，及其使等待或准备失效的原因。

产品诊断使用匿名operation ordinal、target generation或epoch关联，不能输出正文、原始Session/消息ID、URL或凭据。事件数量有界，真实计数精确保留；发生截断需明确标不足，不能静默丢掉中断或延期记录。不得靠逐callback打印日志来改变正在测的滚动负载。

诊断字段固定在可选`schedulerEvents`数组，独立上限256，`schedulerEventLimitReached`明确是否溢出；旧schema3生命周期数组及上限不变。每条含`elapsedSeconds/stage/loadOrdinal/generation/entries/batchEntries/insertedEntries/remainingEntries/moving/changedExistingCount`，有active operation时还含`acceptedAgeMs`，首次buffer后还含`bufferedAgeMs`。stage仅允许`accepted/target_buffered/idle_wait/interrupted/prepare_admitted/preparation_finished/commit_admitted/batch_presented/final_ack/cancelled/idle_drained`。初次`target_buffered.entries=0`表示尚未物化展示行，后续可同generation记录真实行数；两次记录不重置年龄。

`commit_admitted/batch_presented`还须给出显式`route`及`insertedBodyEntries/insertedSupplementalEntries`，两者相加等于新增展示行总数。`route=prepend`硬核新增总数≤2，`in_place`不应含新增；`replacement/initial`分别公开完整批规模，不叫作有界prepend。旧IDs仍连续同序、旧revision改变应由产品源码与原生测试证明仍能分批；真正delete/reorder/replacement不得暗中改名为prepend。raw不含ID，分析器不能单凭route字符串独立证明suffix真实性，报告明确此限制。

每次正新增实际commit必须与同generation及`projectionEpoch`的准入、`batch_presented`及原commitEnd数量/时刻配对；首正文批的`firstPresentation=true`，仅页头等supplemental正新增不能冒充首正文。commit准入须有当前required与已issued的同历史epoch。`final_ack`表示该历史target完整呈现，须remaining为0、其generation确实已缓冲，并有原schema3同epoch的实际presented支持；中间partial批不ack。

同一operation允许先完整呈现epoch3并ack、domain仍loading、随后epoch4完整呈现再ack；每个ack均须绑定真实epoch，不能将第一完整target的ack误叫partial提前完成。最终延迟取最新required epoch受实际生命周期支持的ack，最终owner完成还须terminal满足。不断到来的同history epoch实时消息可增加viewport generation，不能因此把已完整呈现的history ack永远判无效；更高required epoch不能由旧ack代替。

无新增nochange可用`commit_admitted(0)→final_ack`，不伪造正数batch。empty无required的操作只需真实terminal与finished(empty)，不伪造呈现；capture仍须另有完整正数分页，empty-only不足。失败、detach、取消、未完成或诊断截断均不能作新体验通过。

报告公开每行准备耗时、每批准入至呈现耗时/最大值、各route规模和次数。事件没有frame/tick编号，因此“每tick1行、准备与commit错tick”仍须源码及原生测试验证，不能只凭这些时间戳声称已独立证明。任何moving=true的prepare/commit准入，以及原实际commitBegin落在gesture窗内，均违反movement准入；结束后不足连续120ms准入亦失败。

## 防无限延期：年龄、首批与最终延迟

至少分别记录以下时间，均相对同一startedAt；发生在正式callback起采之前的操作应保留其真实起始时间或标明起始证据缺失，不把起采时刻冒充请求时刻：

| 字段语义 | 起止与约束 |
|---|---|
| 实际准入时刻 | 真实cache/network请求被接受的时刻，沿用现有Began口径；reserve/no-op不算accepted |
| 首次UI target缓冲时刻 | 首个尚未呈现的完整target到达viewport；与网络/域完成时刻分开 |
| pendingAgeMs | 从本operation最初实际准入至当前观察时刻；新snapshot合并或epoch更新不能重置年龄 |
| bufferedTargetAgeMs | 从首次未呈现target进入本次缓冲周期至当前时刻；同时可另报最新target年龄，但不能用最新年龄遮盖总等待 |
| firstPresentationDelayMs | 最初实际准入至首个含正文新增展示行的批真正commit完成；仅chrome变化不计，未展示为缺失，不填0 |
| finalPresentationDelayMs | 最初实际准入至最新required target的最后展示批真正完成并产生实际呈现确认；中间commit、terminal或准备完成不能替代 |
| operationCompletionDelayMs | 实际准入至terminal与最终呈现均满足、owner释放operation；与final呈现时间分别记录 |
| interruptionCount | 该operation等待/准备/尚未提交批被新的movement打断次数；合并target不清零 |
| 当前与峰值待呈现量 | 剩余展示行、待测高行、已完成展示批数；只能按真实局部target计算，不用整个数据库条数替代 |

空页、失败、取消、切session与detach各记明确outcome；没有正数呈现时，首批/最终呈现延迟为缺失并保留结束/失败时间，不伪造一次完成呈现。已展示部分不能因新手势撤回；未展示部分仍属同operation，gate保持busy，最终stamp不提前ack。

新报告同时给出：已完成operation的这些延迟与中断次数，以及采样结束仍pending的年龄/剩余量。持续滑动导致一直未呈现也必须可见，不允许只汇总成功完成者来隐藏饥饿。若没有足够的idle机会，可报告“在本次手势条件下未能完成”，不能计作通过。

## 最小边界验证矩阵

1. 正常60Hz时钟与完整窗口；arrival抖动但display稳定时，分别保留两套统计。
2. display或arrival长间隔横跨movement开始/结束，整段计入movement并触发原长停顿门槛；不能按到达flag移出。
3. 同一callback的display窗跨movement、arrival窗不跨，验证各自归桶；display起点不得错误使用arrival端点。
4. 同一间隔跨多个gesture，聚合movement去重；端点相接的保守归类与公开计数。
5. 首display delta缺失但真实display时间保留；非首缺绝对端点/零delta/倒序/非有限数标不足或错误；首arrival长等待仍不能丢。
6. idle长间隔完整公开：全活跃arrival≥100ms失败，idle display≥100ms仅观察而旧display-cadence仍失败；movement双时钟≥100ms失败，不能隐藏无限等待。旧raw仍可重算原策略，缺新证据不能得到新体验通过。
7. target连续合并、更新epoch、再滚动打断，最早accepted年龄不重置；首批与最终批延迟不同，未完成operation仍出现在报告。
8. staged prepend中间批不ack、latest required最终批才ack；empty/失败/切session无虚假first/finaldelay，旧任务不能给新operation完成。
9. 所有新增诊断均验证默认关闭成本、容量上限/精确drop、无正文/ID泄露、无额外layout/host或正式采样策略改变。

分析器新增边界覆盖缓存0次准备、多次单行准备配1批、正文与supplemental首批区分、两次实际commit/最终ack、显式replacement、缺失字段、未完成年龄与CLI独立文件输出。产品调度、原生测试、UITest驱动与分析器分别冻结源码/参数/签名证据。正式结果须标记采用的合同与策略；现有旧报告按原口径理解，新政策不回写既有raw/summary。

纯Python验证当前115项通过，含原72项完整保留；另以冻结前分析器独立重算4种fixture及7份真实raw，两套旧政策共22例的全部旧结果字段完全一致。新增体验区段也不覆盖任一旧字段，真实旧raw及summary保持原文件不动。此为分析器正确性验证，不是产品原生或性能通过证据。
