#!/usr/bin/env python3
"""Rebuild the OCR-only OpenCV/ONNX native bundle (Python >=3.10).

Requires JDK17, Android SDK35 and NDK28.2.13676358. Downloads pinned source
checkouts into ignored .tooling, then publishes stripped native libraries.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = Path(os.environ.get('WUKONG_BUILD_CACHE', str(ROOT / '.tooling'))).expanduser().resolve()
BUNDLE = ROOT / 'third_party/ocr-native'
PINS = {
    'opencv': ('https://github.com/opencv/opencv.git', '0654a42e19215ef25b1d367d822f3c630447e7c7'),
    'onnxruntime': ('https://github.com/microsoft/onnxruntime.git', '8f7cce3a49fdbdac96e0868b75b7d0159db7ac7f'),
    'eigen': ('https://gitlab.com/libeigen/eigen.git', '1d8b82b0740839c0de7f1242a3585e3390ff5f33'),
}
TRIPLES = {'arm64-v8a': 'aarch64-linux-android', 'armeabi-v7a': 'arm-linux-androideabi',
           'x86': 'i686-linux-android', 'x86_64': 'x86_64-linux-android'}
LINK_FLAGS = '-Wl,-z,max-page-size=16384,-z,common-page-size=16384'


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def source(name):
    folder = WORK / (name + '-source')
    url, commit = PINS[name]
    if not (folder / '.git').exists():
        run(['git', 'init', folder])
        run(['git', '-C', folder, 'fetch', '--depth', '1', url, commit])
        run(['git', '-C', folder, 'checkout', '--detach', 'FETCH_HEAD'])
    actual = subprocess.check_output(['git', '-C', str(folder), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != commit:
        raise RuntimeError(f'{folder}: expected pinned commit {commit}, found {actual}')
    run(['git', '-C', folder, 'diff', '--quiet'])
    return folder


def verify_models():
    folder = ROOT / 'third_party/paddleocr'
    for record in json.loads((folder / 'models-manifest.json').read_text()):
        data = (folder / 'src/main/assets/models' / record['path']).read_bytes()
        if hashlib.sha256(data).hexdigest() != record['sha256']:
            raise RuntimeError('Model changed; regenerate required operators and validate: ' + record['path'])


def publish_java(gradle_cache):
    for group, artifact, version, output in [
        ('org.opencv', 'opencv', '4.14.0', 'opencv-java-4.14.0.jar'),
        ('com.microsoft.onnxruntime', 'onnxruntime-android', '1.21.1', 'onnxruntime-java-1.21.1.jar'),
    ]:
        files = list((gradle_cache / group / artifact / version).rglob('*.aar'))
        if not files:
            raise RuntimeError(f'Populate Gradle cache for {group}:{artifact}:{version} before --publish-java')
        with zipfile.ZipFile(files[0]) as archive:
            (BUNDLE / 'libs').mkdir(parents=True, exist_ok=True)
            (BUNDLE / 'libs' / output).write_bytes(archive.read('classes.jar'))
            if group == 'org.opencv':
                for entry in archive.infolist():
                    if entry.filename.startswith('res/') and not entry.is_dir():
                        target = BUNDLE / 'src/main' / entry.filename
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(archive.read(entry))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', nargs='+', choices=list(TRIPLES), default=list(TRIPLES))
    parser.add_argument('--jobs', type=int, default=4)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or str(Path.home() / ('Library/Android/sdk' if sys.platform == 'darwin' else 'Android/Sdk'))))
    parser.add_argument('--jdk', type=Path, default=Path(os.environ.get('JAVA_HOME', str(Path.home() / 'Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home'))))
    parser.add_argument('--publish-java', action='store_true', help='Copy exact upstream Java API JARs from Maven AAR cache')
    args = parser.parse_args()
    if sys.version_info < (3, 10):
        raise RuntimeError('Python 3.10+ is required')
    verify_models()
    venv = WORK / 'native-build-venv'
    python = venv / 'bin/python'
    if not python.exists():
        run([sys.executable, '-m', 'venv', venv])
        run([python, '-m', 'pip', 'install', 'cmake==3.31.6', 'ninja==1.11.1.3',
             'onnx==1.17.0', 'onnxruntime==1.21.1'])
    env = os.environ.copy()
    env['JAVA_HOME'] = str(args.jdk.resolve())
    env['ANDROID_HOME'] = str(args.sdk.resolve())
    env['PATH'] = str(venv / 'bin') + os.pathsep + str(args.jdk / 'bin') + os.pathsep + env['PATH']
    cmake = venv / 'bin/cmake'
    ndk = args.sdk / 'ndk/28.2.13676358'
    host_dirs = list((ndk / 'toolchains/llvm/prebuilt').iterdir())
    if len(host_dirs) != 1:
        raise RuntimeError('Expected one NDK host toolchain')
    tools = host_dirs[0]
    cv = source('opencv')
    ort = source('onnxruntime')
    eigen = source('eigen')
    run(['git', '-C', ort, 'submodule', 'update', '--init', '--recursive'])
    for abi in args.abi:
        cv_build = WORK / ('opencv-trim-arm64' if abi == 'arm64-v8a' else 'opencv-trim-' + abi)
        run([cmake, '-S', cv, '-B', cv_build, '-G', 'Ninja',
             '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
             '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_shared',
             '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_LIST=core,imgproc,java',
             '-DBUILD_SHARED_LIBS=OFF', '-DBUILD_FAT_JAVA_LIB=ON',
             '-DBUILD_ANDROID_PROJECTS=OFF', '-DBUILD_ANDROID_EXAMPLES=OFF',
             '-DBUILD_TESTS=OFF', '-DBUILD_PERF_TESTS=OFF', '-DBUILD_EXAMPLES=OFF',
             '-DBUILD_opencv_apps=OFF', '-DWITH_KLEIDICV=OFF', '-DWITH_TBB=OFF',
             '-DWITH_OPENCL=OFF', '-DWITH_IPP=OFF', '-DWITH_ITT=OFF', '-DWITH_PROTOBUF=OFF',
             '-DPYTHON3_EXECUTABLE=' + str(python), '-DANDROID_SDK_ROOT=' + str(args.sdk),
             '-DANDROID_PROJECTS_BUILD_TYPE=GRADLE', '-DCMAKE_SHARED_LINKER_FLAGS=' + LINK_FLAGS], env=env)
        run([cmake, '--build', cv_build, '--target', 'opencv_java', '--parallel', str(args.jobs)], env=env)
        ort_build = WORK / ('ort-trim-arm64' if abi == 'arm64-v8a' else 'ort-trim-' + abi)
        run([python, ort / 'tools/ci_build/build.py', '--build_dir', ort_build,
             '--config', 'Release', '--update', '--build', '--parallel', str(args.jobs),
             '--cmake_generator', 'Ninja', '--android', '--android_abi', abi, '--android_api', '29',
             '--android_sdk_path', args.sdk, '--android_ndk_path', ndk, '--android_cpp_shared',
             '--build_shared_lib', '--build_java', '--skip_tests', '--skip_submodule_sync',
             '--include_ops_by_config', BUNDLE / 'required-operators.config', '--disable_ml_ops',
             '--disable_rtti', '--enable_lto', '--cmake_extra_defines',
             'CMAKE_SHARED_LINKER_FLAGS=' + LINK_FLAGS,
             'FETCHCONTENT_SOURCE_DIR_EIGEN=' + str(eigen), 'onnxruntime_BUILD_UNIT_TESTS=OFF'], env=env)
        target = BUNDLE / 'src/main/jniLibs' / abi
        target.mkdir(parents=True, exist_ok=True)
        for src in [cv_build / 'jni' / abi / 'libopencv_java4.so',
                    ort_build / 'Release/libonnxruntime.so', ort_build / 'Release/libonnxruntime4j_jni.so',
                    tools / 'sysroot/usr/lib' / TRIPLES[abi] / 'libc++_shared.so']:
            dest = target / src.name
            temporary = target / (src.name + ".tmp")
            shutil.copyfile(src, temporary)
            run([tools / 'bin/llvm-strip', '--strip-unneeded', temporary])
            temporary.replace(dest)
    if args.publish_java:
        cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
        publish_java(cache)
    files = []
    for path in sorted((BUNDLE / 'src/main/jniLibs').glob('*/*.so')) + sorted((BUNDLE / 'libs').glob('*.jar')):
        files.append(dict(path=str(path.relative_to(BUNDLE)), bytes=path.stat().st_size,
                          sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    manifest = dict(sources={k: dict(url=v[0], commit=v[1]) for k, v in PINS.items()},
                    ndk='28.2.13676358', api=29, opencvModules=['core', 'imgproc', 'java'],
                    kleidicv=False, opencl=False, onnxLto=True,
                    operatorConfigSha256=hashlib.sha256((BUNDLE / 'required-operators.config').read_bytes()).hexdigest(),
                    files=files)
    (BUNDLE / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    main()
