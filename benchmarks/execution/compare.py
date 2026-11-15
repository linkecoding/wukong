#!/usr/bin/env python3
"""Compare complete paired reports and their unfiltered traces, including failed cells."""
import argparse
from collections import Counter
import json
from pathlib import Path
from statistics import mean


def load(path):
    report = json.loads(path.read_text())
    if not report.get('finished') or len(report['runs']) != len(report['cases']) * report['repetitions'] * 2:
        raise SystemExit(f'Incomplete report: {path}')
    pairs = Counter((r['case'], r['mode'], r['repetition']) for r in report['runs'])
    expected = {(c, m, n) for c in report['cases'] for m in ('TOOLS_ONLY', 'SCRIPT_ONLY') for n in range(report['repetitions'])}
    if set(pairs) != expected or any(n != 1 for n in pairs.values()):
        raise SystemExit(f'Unbalanced report: {path}')
    return report


def sdk_stats(path, report):
    calls, durations, errors, scripts = Counter(), Counter(), Counter(), Counter()
    timeouts = 0
    for index, row in enumerate(report['runs'], 1):
        trace = path.parent / 'traces' / row['log']
        if not trace.exists():
            trace = path.parent / 'traces' / f"{index:02d}-{row['case']}-{row['mode'].lower()}.jsonl"
        events = [json.loads(line) for line in trace.read_text().splitlines() if line.strip()]
        configs = [e['data'] for e in events if e['type'] == 'benchmark_config']
        if len(configs) != 1 or any(configs[0][k] != row[k] for k in ('case', 'mode', 'repetition')):
            raise SystemExit(f'Trace/report mismatch: {trace}')
        if not any(e['type'] == 'end' for e in events):
            raise SystemExit(f'Missing trace end: {trace}')
        for event in events:
            kind, data = event['type'], event['data']
            if kind == 'script_call_result':
                method = data['method']
                calls[method] += 1
                durations[method] += data.get('durationMs', 0)
                if isinstance(data.get('result'), dict) and data['result'].get('timedOut'):
                    timeouts += 1
            elif kind == 'script_error':
                errors[data.get('diagnostic', {}).get('code', 'UNSTRUCTURED')] += 1
            elif kind == 'tool_call' and isinstance(data.get('arguments'), dict):
                code = data['arguments'].get('code')
                if code:
                    scripts['cells'] += 1
                    for name in ('waitAny', 'setText', 'setChecked', 'id', 'text', 'textContains'):
                        scripts[name] += f'wukong.{name}(' in code
    return {'completedCalls': dict(calls), 'durationMs': dict(durations), 'waitTimeouts': timeouts,
            'structuredErrors': dict(errors), 'cellsUsingApis': dict(scripts)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('current', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    before, after = load(args.baseline), load(args.current)
    for field in ('model', 'provider', 'thinking', 'device', 'android', 'repetitions', 'cases'):
        if before[field] != after[field]:
            raise SystemExit(f'Comparison setting differs: {field}')
    order = lambda report: [(r['case'], r['mode'], r['repetition']) for r in report['runs']]
    if order(before) != order(after):
        raise SystemExit('Paired ordering differs')
    lines = ['# SDK 优化前后的 12 次真机对比', '',
             f"基线：`{before['sourceRevision']}`；本轮：`{after['sourceRevision']}`。", '',
             '同一设备、模型、思考设置、任务、oracle 和配对顺序；保留所有失败和恢复。接口说明与节点数据随实现更新，服务商缓存、网络和模型输出未固定。', '',
             '| 模式 | 指标 | 优化前 | 优化后 | 变化 |', '| --- | --- | ---: | ---: | ---: |']
    for mode, label in [('TOOLS_ONLY', '纯工具'), ('SCRIPT_ONLY', 'JS')]:
        old = [r for r in before['runs'] if r['mode'] == mode]
        new = [r for r in after['runs'] if r['mode'] == mode]
        lines.append(f"| {label} | 成功 | {sum(r['success'] for r in old)}/{len(old)} | {sum(r['success'] for r in new)}/{len(new)} | — |")
        metrics = [('平均耗时 s', lambda r: r['elapsedMs']/1000), ('平均模型请求耗时 s', lambda r: r['modelTimeMs']/1000),
                   ('平均非模型耗时 s', lambda r: (r['elapsedMs']-r['modelTimeMs'])/1000), ('平均规划轮数', lambda r: r['planningRounds']),
                   ('平均总 token', lambda r: r['totalTokens']), ('平均输入 token', lambda r: r['promptTokens']),
                   ('平均输出 token', lambda r: r['completionTokens']), ('平均缓存命中 token', lambda r: r['cacheHitTokens'])]
        if not all(r['usageComplete'] for r in old + new):
            raise SystemExit('Missing usage; do not compute token averages')
        for name, getter in metrics:
            a, b = mean(map(getter, old)), mean(map(getter, new))
            delta = f'{(b/a-1)*100:+.1f}%' if a else '—'
            lines.append(f'| {label} | {name} | {a:,.2f} | {b:,.2f} | {delta} |')
        for field, name in [('scriptErrors','脚本错误'), ('deviceErrors','设备错误'), ('falseCompletion','误报完成'), ('mutationsAccepted','已接受设备操作')]:
            a, b = sum(r[field] for r in old), sum(r[field] for r in new)
            lines.append(f'| {label} | {name} | {a} | {b} | {b-a:+d} |')
    old_stats, new_stats = sdk_stats(args.baseline, before), sdk_stats(args.current, after)
    lines += ['', '## JS 等待与调用', '', '| 指标（全部 JS 运行累计） | 优化前 | 优化后 |', '| --- | ---: | ---: |']
    for name, a, b in [
        ('sleep 次数', old_stats['completedCalls'].get('sleep',0), new_stats['completedCalls'].get('sleep',0)),
        ('sleep 耗时 ms', old_stats['durationMs'].get('sleep',0), new_stats['durationMs'].get('sleep',0)),
        ('query 次数', old_stats['completedCalls'].get('query',0), new_stats['completedCalls'].get('query',0)),
        ('条件等待超时次数', old_stats['waitTimeouts'], new_stats['waitTimeouts'])]:
        lines.append(f'| {name} | {a} | {b} |')
    lines += ['', '详细调用计数与新 API 使用情况见 [sdk-metrics.json](sdk-metrics.json)。', '',
              '## 解释限制', '',
              '- 耗时包含模型请求；同时列出非模型耗时，避免把网络变化全部归因于 SDK。',
              '- sleep 包含真实加载等待，次数越少不自动等于正确性更好；还须核对查询顺序、最终状态和错误恢复。',
              '- token 来自完整 provider usage，缓存没有关闭，总 token 不等于实际费用。',
              '- 每个任务每种模式仅两次，属于工程回归对比，不构成统计显著性或任意 App 上的普遍结论。', '']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text('\n'.join(lines))
    args.output.with_name('sdk-metrics.json').write_text(json.dumps({'baseline':old_stats,'current':new_stats},ensure_ascii=False,indent=2)+'\n')


if __name__ == '__main__':
    main()
