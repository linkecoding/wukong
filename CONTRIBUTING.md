# Contributing to Wukong

Thanks for helping improve Wukong. Bug reports, device compatibility results, documentation corrections and focused pull requests are welcome. English and Chinese are both welcome in issues and PRs.

## Before contributing

Read the [README](README.md), [disclaimer](DISCLAIMER.md) and relevant [architecture notes](docs/architecture.md). Wukong's own source does not yet have a selected open-source license; public visibility is not a blanket license grant. Third-party licenses remain applicable. Until licensing is settled, discuss substantial code contributions with the maintainer in an issue before investing in them. Do not submit code or assets you cannot authorize the project to use; no copyright transfer or CLA is currently specified.

## Report a bug

Use the bug-report template and include the app version, Android version, device model, main/virtual display, provider/model ID, minimal task and expected/actual behavior. Never include an API key, pairing code, ADB identity, signing key, password or private account data.

If log retention was enabled before the run, export that run from its task card or History → Execution details → top-right menu. Inspect `events.jsonl` and `manifest.json` before attaching the ZIP: task text and tool arguments/results can contain private information. Screenshots and recordings also need review. For vulnerabilities, follow [SECURITY.md](SECURITY.md) instead of posting exploitation details publicly.

## Development

Use JDK 17, SDK 35, Build Tools 35.0.0, NDK 28.2.13676358 and CMake 3.22.1. See [development setup](docs/development.md). Keep credentials and signing material outside Git; production keys are not needed to build debug APKs.

```sh
./scripts/gradle-local.sh -PtargetAbi=arm64-v8a \
  :agent-core:test :shell-runtime:testDebugUnitTest :app:testDebugUnitTest \
  :app:lintDebug :shell-runtime:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Use a branch and keep each change focused. Explain the problem, resulting behavior and validation in the PR. Update docs and English/Chinese strings when behavior changes. Keep Android control in the existing built-in shell architecture; preserve cancellation, takeover, snapshot validation and high-risk approvals.

Add meaningful regression coverage for behavior changes. UI changes should be checked in English/Chinese, with keyboard and Back navigation. Input changes need actual coordinate/virtual-display verification where possible. Document what you could not test. Connected model tests are opt-in, require a configured device and may cost API tokens; never run them against a contributor's phone without authorization. Do not perform payments, destructive operations or external communication as test fixtures.

CI checks compilation, unit tests and lint. Maintainers handle version bumps, production signing and release tags; never add signing credentials to a PR. See [release procedure](docs/releasing.md).

## 简体中文

欢迎提交问题、机型兼容性记录、文档修正和聚焦的 PR。反馈请提供版本、系统、机型、执行屏幕、模型 ID、复现任务和预期/实际结果。日志、截图分享前请检查隐私，不要提交密钥或配对信息。较大的代码贡献请先开 issue 讨论；项目自身代码许可证尚未确定，第三方许可证仍有效。开发与验证要求见上文，安全问题见 [SECURITY.md](SECURITY.md)。
