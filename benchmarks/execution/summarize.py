#!/usr/bin/env python3
"""Summarize paired device runs. Includes failed attempts; never treats missing usage as zero."""
import argparse
import json
from pathlib import Path
from statistics import mean, median


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    runs = report['runs']
    if not report.get('finished'):
        raise SystemExit('Report is incomplete; do not publish a final summary.')
    cases = list(report['cases'])
    modes = ['TOOLS_ONLY', 'SCRIPT_ONLY']
    for case in cases:
        pairs = {mode: [r for r in runs if r['case'] == case and r['mode'] == mode] for mode in modes}
        if any(len(rows) != report['repetitions'] for rows in pairs.values()):
            raise SystemExit(f'Unbalanced report: {case}')
    lines = ['# 真机：纯工具与 JS 编程调用对比', '',
             f"模型：`{report['model']}`；服务商：`{report['provider']}`；思考模式：`{report['thinking']}`；设备：{report['device']} / Android {report['android']}。", '',
             '三类可复现原生界面任务，每类两种模式各重复相同次数，配对交替先后顺序。模型自行决策／生成代码，使用真实 shell 读屏输入和 QuickJS。', '',
             '| 任务 | 模式 | 成功 | 平均耗时 s | 中位耗时 s | 平均规划轮数 | 平均总 token | 页面无效操作 | 误报完成 |',
             '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    names = {'batch': '批量查询回填', 'retry': '查询失败后局部重试', 'wizard': '跨页填写校验'}
    def token_mean(rows):
        return f"{mean(r['totalTokens'] for r in rows):,.0f}" if all(r['usageComplete'] and r['totalTokens'] is not None for r in rows) else '用量缺失'
    def row(label, mode, rows):
        return f"| {label} | {'纯工具' if mode == 'TOOLS_ONLY' else 'JS'} | {sum(r['success'] for r in rows)}/{len(rows)} | {mean(r['elapsedMs'] for r in rows)/1000:.2f} | {median(r['elapsedMs'] for r in rows)/1000:.2f} | {mean(r['planningRounds'] for r in rows):.1f} | {token_mean(rows)} | {sum(r['objective']['invalidActions'] for r in rows)} | {sum(r['falseCompletion'] for r in rows)} |"
    for case in cases:
        for mode in modes:
            lines.append(row(names.get(case, case), mode, [r for r in runs if r['case'] == case and r['mode'] == mode]))
    for mode in modes:
        lines.append(row('全部', mode, [r for r in runs if r['mode'] == mode]))
    lines += ['', '## 逐次结果', '',
              '| 次序 | 任务 | 重复编号 | 模式 | 成功 | 秒 | 规划轮数 | HTTP 请求 | token | JS 错误 | 设备错误 |',
              '| --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for index, r in enumerate(runs, 1):
        lines.append(f"| {index} | {names.get(r['case'], r['case'])} | {r['repetition']+1} | {'工具' if r['mode']=='TOOLS_ONLY' else 'JS'} | {'是' if r['success'] else '否'} | {r['elapsedMs']/1000:.2f} | {r['planningRounds']} | {r['httpRequests']} | {r['totalTokens'] if r['usageComplete'] else '缺失'} | {r['scriptErrors']} | {r['deviceErrors']} |")
    lines += ['', '## 指标口径和限制', '',
              '- 成功要求任务引擎返回 COMPLETED，且独立页面校验通过。查询顺序与次数必须符合目标，不能用重复成功查询凑出结果；字段、复选框和最终状态直接读取 Android View 校验。无效校验/关键词操作单独计数，纠正后的最终成功不因此抹掉。',
              '- 规划轮数是工具调用响应数；HTTP 请求还包括重试和独立完成复核。总 token 直接累计服务商 usage，包含生成脚本和复核的用量，不估算缺失值。缓存命中量、原始 usage、每次运行日志名在 JSON 中保留。',
              '- 表中耗时和 token 包含失败尝试，没有仅筛选成功样本。超时上限 150 秒加现有在途传输清理时间；单任务两组均为最多 30 个模型回合。JS 每段沿用 60 秒 / 80 次 API 调用限制。',
              '- 两组均采用自动观测和相同的审批、取消与完成验证。纯工具组提供全部单步/查询/等待/视觉工具；JS 组提供 JS 和 finish，所有手机能力走 JS。没有预写脚本供模型照抄。',
              '- 两组的接口文档长度不同，这是实际工具形态的上下文成本。已交替顺序但未禁用服务商缓存；temperature 沿用产品默认、网络延迟和模型输出存在波动。每种任务样本很少，不代表任意 App/长任务的普遍成功率或统计显著性。',
              '- 这是原生 Android View 的控制流程评估，覆盖加载、错误分支和跨页操作；没有覆盖第三方 App 的自绘控件、验证码、真实发送支付、复杂视觉识别或多 App 跳转。', '']
    text = '\n'.join(lines)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    else:
        print(text)


if __name__ == '__main__':
    main()
