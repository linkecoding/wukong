# 真机执行方式对比

比较悟空当前的两种调用形态：

- **纯工具**：每轮选择一个单步操作、查询、条件等待或视觉工具，不提供 JS。
- **编程**：设备操作、观察与等待全部通过模型生成的 QuickJS 脚本执行，仅另外提供 finish。没有预写脚本交给模型。

App 日常任务保持 `PlanningMode.AUTO`，此次对比没有新增用户设置项。`PlanningMode` 用于明确约束请求工具集合和执行入口；不能仅靠提示词声称“禁用某个工具”。结束验证和受保护操作规则由两组共享。

[2026-11-15 结论与错误分析](results/2026-11-15/analysis.md) · [实测逐次表格](results/2026-11-15/README.md) · [原始汇总 JSON](results/2026-11-15/report.json) · [12 次运行轨迹](results/2026-11-15/traces)

## SDK 优化复测

[最终复测与剩余问题](results/2026-11-15-sdk-final/analysis.md) · [前后指标](results/2026-11-15-sdk-final/comparison.md) · [首轮失败记录](results/2026-11-15-sdk-initial/analysis.md)。原始基线不变，两个新增完整 12 次分别保存；修复后 12/12 成功，JS 平均耗时下降约 28%，token 仅下降约 2%。纯工具组未改善。

从两份完整报告及对应 traces 重新生成对比：

```sh
python3 benchmarks/execution/compare.py \
  benchmarks/execution/results/2026-11-15/report.json \
  benchmarks/execution/results/2026-11-15-sdk-final/report.json \
  --output benchmarks/execution/results/2026-11-15-sdk-final/comparison.md
```

## 任务与判定

在 Pixel 6 上运行真实 Android View，底层使用 App 内相同的 shell 驱动、输入注入和 QuickJS。三个任务均为合成数据，避免真实发送、支付和用户业务数据：

1. **批量查询回填**：依次查询三个关键词，读取各自件数，写入对应字段，再核对结果。至少 10 个有效设备变更。
2. **错误分支**：同一流程，但第二个关键词首次必定失败，必须只重试该项一次，成功查询不能重复。至少 11 个有效设备变更。
3. **跨页表单**：填写姓名、城市，关闭复选框；等待进入第二页，填写时间、人数并选择步行，核对最终结果。至少 8 个有效设备变更。

独立 oracle 读取实际 View 状态、字段、查询调用序列与校验次数。引擎 COMPLETED 且 oracle 通过才算成功；最终填写正确但中途重复了明确禁止重复的查询仍不通过。可恢复的错误不会被丢弃。摘要另外人工核对，12 次结果均与实际件数/表单相符。

每类两组各运行两次，共 12 次；第一组工具→JS，第二组 JS→工具，第二组还反转任务次序。使用同一加密配置中的模型、同一思考设置和相同任务目标。报告固定记录实际模型 ID、设置、顺序、源文件哈希、原始 usage 与日志路径。未禁用服务商提示词缓存，不能把总 token 等同于费用。

## 复现

准备已解锁、已激活 shell 且配置好模型的测试手机；脚本使用默认显示器，运行期间保持测试页面前台。通过默认用户配置连接真实模型，会产生模型请求。测试仅在传入 `live_benchmark=true` 时执行，普通测试不会自动跑付费评估。

```sh
./scripts/gradle-local.sh -PtargetAbi=arm64-v8a \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 先检查工具集合策略和测试页面的 oracle，不调用模型。
adb -s SERIAL shell am instrument -w \
  -e class 'com.wukong.phone.ExecutionComparisonTest#capabilityPoliciesAreEnforcedInRequestSchemas,com.wukong.phone.ExecutionComparisonTest#fixtureOraclesRecognizeSuccessAndTheRequiredRetry' \
  com.wukong.phone.test/androidx.test.runner.AndroidJUnitRunner

# 真实模型 12 次配对。
adb -s SERIAL shell am instrument -w \
  -e live_benchmark true -e repetitions 2 \
  -e report execution-comparison.json \
  -e class 'com.wukong.phone.ExecutionComparisonTest#comparePairedComplexWorkflowsWithTheConfiguredModel' \
  com.wukong.phone.test/androidx.test.runner.AndroidJUnitRunner

adb -s SERIAL shell run-as com.wukong.phone cat files/execution-comparison.json > report.json
python3 benchmarks/execution/summarize.py report.json --output summary.md
```

可用 `-e cases batch,retry,wizard` 选择任务；`repetitions` 范围 1–5。测试每完成一次就原子更新汇总文件，并输出 BENCH 进度。单次最多 150 秒，加现有在途传输清理时间。完整轨迹位于 App 私有目录 `files/run-logs-benchmark/`，与用户普通任务日志分开保留，不修改历史记录或模型配置。测试结束恢复虚拟屏启用偏好。

调试资源 ID 仅放在 `src/debug`，release 不包含测试页面。真实评估失败会写入结果，不会因失败就自动重跑或剔除。没有建立对外开放的 benchmark/JS 执行接口。
