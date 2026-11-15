# 悟空内置 SPAKE2

来源：MuntashirAkon/spake2-java 2.2.1 的 Android 实现，包含该标签固定的 spake2-c 子模块提交 `0d15933e5ba3e662cb01245a7ac0dc9fca3eac31`。保留 LGPL-3.0 及各源码文件的原有版权和许可证。

悟空修改了构建配置：采用 NDK 28.2.13676358、CMake 3.22.1、静态 C++ 运行库，并显式设置 16 KB 链接对齐。协议算法没有修改。完整源代码在此目录，修改后直接运行项目 Gradle 即可重新链接到悟空 APK；对应源码也随 APK 以 ZIP 提供。
