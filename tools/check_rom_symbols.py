#!/usr/bin/env python3
"""检查 HyperLock 的 hook 目标在当前 ROM 上是否仍然存在。

解决的问题：模块用大量字符串常量指向系统内部类、方法名与字段名，ROM 升级后某处改名或
被移除时，反射只会抛异常并被 runCatching 吞掉，表现为「功能静默失效」。
本脚本把「当前 ROM 还认不认这些符号」变成一条可重复执行的命令。

用法：
    python tools/check_rom_symbols.py --systemui <SystemUI.apk> [--aod <com.miui.aod.apk>]
        [--framework <framework.jar>] [--systemui-res <res 目录>]

校验范围：
    1. 常量类名（`*_CLASS` 等 `const val "com.x.Y"`）与常量成员名（`*_METHOD` / `*_FIELD`）；
    2. inline 反射目标：`loadClass("…")` / `Class.forName("…")` / `getMethod("…")` 等字面量
       （常量扫描看不到这一类，ROM 改名后会静默失效）；
    3. 资源名：`*_RESOURCES` / `*_ARRAY_NAMES` / `*_RESOURCE` 常量与 `getIdentifier("名", "类型")` 字面量。

选项：
    --framework    framework.jar（含 classes*.dex），提供后成员名可在框架侧判定，否则未命中两
                   个 APK 的成员会记为 SKIP 而不是 MISS。
    --systemui-res jadx 导出的 res/values 目录；提供后逐个核对资源名。两者都会在仓库内自动探测
                   （`systemui/framework.jar`、`.jadx-systemui-res/resources/res`）。

退出码：任一类目标缺失时为 1，否则为 0。
"""

from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):  # 非标准输出流时忽略
    pass

# com.miui.keyguard.editor.* 位于息屏与锁屏编辑应用，其余目标都在 SystemUI。
AOD_PREFIXES = ("com.miui.keyguard.editor.",)

# 框架类不在 SystemUI / AOD 的 dex 里，需要 framework.jar 才能校验。
FRAMEWORK_PREFIXES = ("android.", "androidx.")

# 只把系统包名下的字符串当作 hook 目标，避免把本项目自己的常量当成目标。
SYSTEM_PREFIXES = ("com.android.", "com.miui.", "miui.", "android.", "androidx.")

# 形如 com.android.keyguard.shortcut.MiuiShortcutController，允许 $ 表示内部类。
CLASS_PATTERN = re.compile(r"^(?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_$]*$")

# 从常量名判断它在描述什么：*_CLASS 是类，*_METHOD / *_FIELD 是成员名。
# 允许换行：部分常量把长字符串写在下一行。
CONST_PATTERN = re.compile(r'const\s+val\s+([A-Z][A-Z0-9_]*)\s*=\s*\n?\s*"([^"]+)"')

# inline 反射目标：加载类与查询成员时直接写的字面量（允许跨行写法）。
INLINE_CLASS_PATTERN = re.compile(r'(?:loadClass|forName)\(\s*"([^"]+)"')
INLINE_MEMBER_PATTERN = re.compile(r'(?:getMethod|getDeclaredMethod|getDeclaredField|getField)\(\s*"([^"]+)"')

# inline 字面量里允许出现的系统包前缀（比常量扫描多一个 kotlinx：协程 Flow 代理目标）。
INLINE_CLASS_PREFIXES = SYSTEM_PREFIXES + ("kotlinx.",)

# 资源名：*_RESOURCES / *_ARRAY_NAMES 列表、*_RESOURCE 单值，以及直传字面量的查询点。
RESOURCE_LIST_PATTERN = re.compile(
    r'val\s+([A-Z][A-Z0-9_]*(?:_RESOURCES|_ARRAY_NAMES))\s*(?::[^=]+)?=\s*listOf\(([^)]*)\)',
)
RESOURCE_SINGLE_PATTERN = re.compile(r'val\s+([A-Z][A-Z0-9_]*_RESOURCE)\s*(?::[^=]+)?=\s*"([^"]+)"')
RESOURCE_ARG_PATTERN = re.compile(r'(?:getIdentifier|resourceId)\(\s*"([^"]+)"\s*,\s*"([^"]+)"')

# 资源表里的 (name, type)：public.xml 的 type 属性 + values/*.xml 的标签名。
RESOURCE_XML_PUBLIC_PATTERN = re.compile(r'<public\s+type="([^"]+)"\s+name="([^"]+)"')
RESOURCE_XML_DECL_PATTERN = re.compile(
    r'<(color|integer|dimen|string|bool|array|string-array|integer-array)\s+name="([^"]+)"',
)

DEX_HEADER_STRING_IDS_SIZE_OFFSET = 0x38
DEX_HEADER_STRING_IDS_OFFSET = 0x3C
CLASS_DESCRIPTOR = "L{name};"


def read_uleb128(data: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte & 0x80 == 0:
            return result, offset
        shift += 7


def decode_dex_string(data: bytes, offset: int) -> str:
    """读取 string_data_item：uleb128 长度 + MUTF-8 字节，以 NUL 结束。"""
    _, offset = read_uleb128(data, offset)
    end = offset
    while end < len(data) and data[end] != 0:
        end += 1
    raw = data[offset:end]
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        # MUTF-8 里的代理对等非法序列按 latin-1 兜底，只用于精确匹配 ASCII 符号名。
        return raw.decode("latin-1")


def dex_strings(dex: bytes) -> set[str]:
    string_ids_size = int.from_bytes(
        dex[DEX_HEADER_STRING_IDS_SIZE_OFFSET:DEX_HEADER_STRING_IDS_SIZE_OFFSET + 4],
        "little",
    )
    string_ids_off = int.from_bytes(
        dex[DEX_HEADER_STRING_IDS_OFFSET:DEX_HEADER_STRING_IDS_OFFSET + 4],
        "little",
    )
    strings: set[str] = set()
    for index in range(string_ids_size):
        entry = string_ids_off + index * 4
        data_off = int.from_bytes(dex[entry:entry + 4], "little")
        strings.add(decode_dex_string(dex, data_off))
    return strings


def apk_strings(apk: Path) -> tuple[set[str], list[str]]:
    strings: set[str] = set()
    dex_names: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                dex_names.append(name)
                strings |= dex_strings(archive.read(name))
    return strings, dex_names


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def collect_targets(sources: Path) -> tuple[dict[str, str], dict[str, str], dict[str, str]]:
    """返回 (类目标, 成员目标, 资源目标)，键为符号、值为来源（常量名或 文件:行）。"""
    classes: dict[str, str] = {}
    members: dict[str, str] = {}
    resources: dict[str, str] = {}
    for path in sorted(sources.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        for const_name, raw_value in CONST_PATTERN.findall(text):
            # Kotlin 源码里的 \$ 是转义写法，还原成 DEX 中的实际字符。
            value = raw_value.replace("\\$", "$").replace("\\\\", "\\")
            if CLASS_PATTERN.match(value):
                # 本项目的自有常量（如广播 action）不是 hook 目标。
                if not value.startswith(SYSTEM_PREFIXES):
                    continue
                classes.setdefault(value, const_name)
            elif const_name.endswith(("_METHOD", "_FIELD")) and value.isidentifier():
                members.setdefault(value, const_name)

        # inline 反射目标：常量扫描看不到，ROM 改名时同样会静默失效。
        for match in INLINE_CLASS_PATTERN.finditer(text):
            value = match.group(1)
            if CLASS_PATTERN.match(value) and value.startswith(INLINE_CLASS_PREFIXES):
                classes.setdefault(value, f"{path.name}:{line_of(text, match.start())}")
        for match in INLINE_MEMBER_PATTERN.finditer(text):
            value = match.group(1)
            if value.isidentifier():
                members.setdefault(value, f"{path.name}:{line_of(text, match.start())}")

        # 资源名：模块在运行时用名字去查资源表，改名后同样是静默失效。
        for const_name, block in RESOURCE_LIST_PATTERN.findall(text):
            for value in re.findall(r'"([^"]+)"', block):
                resources.setdefault(value, const_name)
        for const_name, value in RESOURCE_SINGLE_PATTERN.findall(text):
            resources.setdefault(value, const_name)
        for name, resource_type in RESOURCE_ARG_PATTERN.findall(text):
            resources.setdefault(name, f"{path.name} ({resource_type})")
    return classes, members, resources


def resource_index(res_dir: Path) -> dict[str, set[str]] | None:
    """读取 jadx 导出的 res 目录，返回 资源名 -> {type}；目录不存在时返回 None。"""
    if not res_dir.is_dir():
        return None
    index: dict[str, set[str]] = {}
    for path in sorted(res_dir.rglob("*.xml")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for resource_type, name in RESOURCE_XML_PUBLIC_PATTERN.findall(text):
            index.setdefault(name, set()).add(resource_type)
        for resource_type, name in RESOURCE_XML_DECL_PATTERN.findall(text):
            index.setdefault(name, set()).add(resource_type)
    return index or None


def scope_of(symbol: str) -> str:
    if symbol.startswith(FRAMEWORK_PREFIXES):
        return "framework"
    return "aod" if symbol.startswith(AOD_PREFIXES) else "systemui"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--systemui", required=True, type=Path, help="SystemUI APK 路径")
    parser.add_argument("--aod", type=Path, help="com.miui.aod APK 路径（可选）")
    parser.add_argument("--framework", type=Path, help="framework.jar 路径（可选，用于判定成员名归属）")
    parser.add_argument("--systemui-res", type=Path, help="jadx 导出的 res/values 目录（可选，用于核对资源名）")
    parser.add_argument(
        "--sources",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "java",
        help="Kotlin 源码目录",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    if args.framework is None:
        auto_framework = repo_root / "systemui" / "framework.jar"
        if auto_framework.is_file():
            args.framework = auto_framework
            print(f"自动使用框架包：{args.framework}")
    if args.systemui_res is None:
        auto_res = repo_root / ".jadx-systemui-res" / "resources" / "res"
        if auto_res.is_dir():
            args.systemui_res = auto_res
            print(f"自动使用资源目录：{args.systemui_res}")

    systemui_strings, dex_names = apk_strings(args.systemui)
    aod_strings: set[str] = set()
    if args.aod is not None:
        aod_strings, _ = apk_strings(args.aod)

    framework_strings: set[str] = set()
    framework_available = False
    if args.framework is not None:
        framework_strings, framework_dex_names = apk_strings(args.framework)
        framework_available = bool(framework_dex_names)
        if not framework_available:
            print(f"警告：{args.framework} 内没有 classes*.dex，框架成员将按 SKIP 处理")

    resources_table = resource_index(args.systemui_res) if args.systemui_res is not None else None

    classes, members, resources = collect_targets(args.sources)
    print(f"SystemUI: {args.systemui} ({', '.join(dex_names) or '无 dex'})")
    if args.aod is not None:
        print(f"AOD: {args.aod}")
    if args.framework is not None:
        print(f"framework: {args.framework}{'' if framework_available else '（无 dex）'}")
    if resources_table is not None:
        print(f"资源表: {args.systemui_res}（{len(resources_table)} 个资源名）")
    print(f"目标: {len(classes)} 个类, {len(members)} 个方法/字段, {len(resources)} 个资源名\n")

    missing_systemui: list[str] = []
    missing_aod: list[str] = []
    missing_framework: list[str] = []
    missing_resources: list[str] = []
    skipped_aod: list[str] = []
    skipped_framework: list[str] = []
    skipped_members: list[str] = []
    skipped_resources: list[str] = []

    def report(kind: str, symbol: str, const_name: str) -> None:
        if kind == "member":
            # 成员名不带包名，无法从字符串本身判断作用域：两个 APK 都查一遍。
            if symbol in systemui_strings:
                print(f"OK     {const_name:<42} {symbol}")
                return
            if symbol in aod_strings:
                print(f"OK     {const_name:<42} {symbol} (AOD)")
                return
            if symbol in framework_strings:
                print(f"OK     {const_name:<42} {symbol} (framework)")
                return
            if not aod_strings or not framework_available:
                reasons = []
                if not aod_strings:
                    reasons.append("需 --aod")
                if not framework_available:
                    reasons.append("需 --framework")
                print(f"SKIP   {const_name:<42} {symbol} (SystemUI 中未找到，{ '、'.join(reasons) } 才能判定)")
                skipped_members.append(f"{const_name} -> {symbol}")
                return
            print(f"MISS   {const_name:<42} {symbol}")
            missing_systemui.append(f"{const_name} -> {symbol}")
            return

        descriptor = CLASS_DESCRIPTOR.format(name=symbol.replace(".", "/")) if kind == "class" else symbol
        scope = scope_of(symbol)
        if scope == "framework":
            if not framework_available:
                skipped_framework.append(f"{const_name} -> {symbol}")
                print(f"SKIP   {const_name:<42} {symbol} (框架类，需 --framework)")
                return
            if descriptor in framework_strings:
                print(f"OK     {const_name:<42} {symbol} (framework)")
                return
            print(f"MISS   {const_name:<42} {symbol}")
            missing_framework.append(f"{const_name} -> {symbol}")
            return
        if scope == "aod" and args.aod is None:
            skipped_aod.append(f"{const_name} -> {symbol}")
            print(f"SKIP   {const_name:<42} {symbol} (未提供 AOD APK)")
            return
        pool = aod_strings if scope == "aod" else systemui_strings
        # 形如 ...KeyguardPanelViewController$nsslLockYPosition_delegate$lambda$ 的常量只描述前缀，
        # 真实类名在后面还带序号，因此改用前缀匹配。
        is_prefix = symbol.endswith("$")
        found = (
            any(entry.startswith(descriptor[:-1]) for entry in pool)
            if is_prefix
            else descriptor in pool
        )
        if found:
            print(f"OK     {const_name:<42} {symbol}")
            return
        print(f"MISS   {const_name:<42} {symbol}")
        (missing_aod if scope == "aod" else missing_systemui).append(f"{const_name} -> {symbol}")

    for symbol, const_name in sorted(classes.items(), key=lambda item: item[1]):
        report("class", symbol, const_name)
    for symbol, const_name in sorted(members.items(), key=lambda item: item[1]):
        report("member", symbol, const_name)
    for name, origin in sorted(resources.items(), key=lambda item: item[1]):
        if resources_table is None:
            skipped_resources.append(f"{origin} -> {name}")
            continue
        types = resources_table.get(name)
        if types:
            print(f"OK     {origin:<42} 资源 {name} ({', '.join(sorted(types))})")
        else:
            print(f"MISS   {origin:<42} 资源 {name}")
            missing_resources.append(f"{origin} -> {name}")
    if skipped_resources:
        print(f"SKIP   {len(skipped_resources)} 个资源名（未提供 --systemui-res）")

    print()
    if missing_systemui:
        print(f"SystemUI 缺失 {len(missing_systemui)} 项：")
        for item in missing_systemui:
            print(f"  - {item}")
    if missing_aod:
        print(f"AOD 缺失 {len(missing_aod)} 项：")
        for item in missing_aod:
            print(f"  - {item}")
    if missing_framework:
        print(f"framework 缺失 {len(missing_framework)} 项：")
        for item in missing_framework:
            print(f"  - {item}")
    if missing_resources:
        print(f"资源缺失 {len(missing_resources)} 项：")
        for item in missing_resources:
            print(f"  - {item}")
    if skipped_aod:
        print(f"跳过 {len(skipped_aod)} 项 AOD 目标（需要用 --aod 指定 APK）")
    if skipped_framework:
        print(f"跳过 {len(skipped_framework)} 项框架类目标（需要用 --framework 指定 framework.jar）")
    if skipped_members:
        print(f"跳过 {len(skipped_members)} 项成员目标（需要 --aod 与 --framework 才能判定）")
    if skipped_resources:
        print(f"跳过 {len(skipped_resources)} 项资源名（需要用 --systemui-res 指定资源目录）")
    if not (missing_systemui or missing_aod or missing_framework or missing_resources):
        print("全部目标均存在于对应 ROM 中。")
    return 1 if (missing_systemui or missing_aod or missing_framework or missing_resources) else 0


if __name__ == "__main__":
    sys.exit(main())
