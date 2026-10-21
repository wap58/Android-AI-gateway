#!/usr/bin/env python3
"""天元鼎改动的静态自检（沙箱没有 Kotlin 编译器，用它在提交前挡低级错误）。

检查项：
  1. 括号/花括号/引号平衡
  2. object/class 体为空（改动误删成员）
  3. 跨文件引用：`Type.member(` 中 Type 是项目内类型但 member 不存在
  4. import dev.aigw.* 指向的声明是否存在
  5. 同文件内重复的 fun 定义

用法：python3 selfcheck.py
退出码非 0 表示有问题。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

errors = []
warnings = []


def kt_files():
    for module in ("app/src/main/kotlin", "core/src/main/kotlin", "cli/src/main/kotlin"):
        d = os.path.join(ROOT, module)
        if not os.path.isdir(d):
            continue
        for base, _, names in os.walk(d):
            for n in names:
                if n.endswith(".kt"):
                    yield os.path.join(base, n)


def strip_code(text):
    """去掉注释与字符串内容，只留结构。"""
    out = []
    i, n = 0, len(text)
    state = None
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if state is None:
            if two == "//":
                state = "line"
                i += 2
                continue
            if two == "/*":
                state = "block"
                i += 2
                continue
            if text[i:i + 3] == '"""':
                state = "raw"
                i += 3
                continue
            if c == '"':
                state = "str"
                i += 1
                continue
            if c == "'":
                j = i + 1
                while j < n and text[j] != "'":
                    j += 2 if text[j] == "\\" else 1
                i = j + 1
                continue
            out.append(c)
            i += 1
        elif state == "line":
            if c == "\n":
                state = None
                out.append("\n")
            i += 1
        elif state == "block":
            if two == "*/":
                state = None
                i += 2
            else:
                if c == "\n":
                    out.append("\n")
                i += 1
        elif state == "raw":
            if text[i:i + 3] == '"""':
                state = None
                i += 3
            else:
                i += 1
        elif state == "str":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = None
            i += 1
    return "".join(out)


def check_balance(path, code):
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in "([{":
            stack.append((ch, line))
        elif ch in ")]}":
            if not stack:
                errors.append(f"{path}:{line} 多余的 {ch}")
                return
            op, ol = stack.pop()
            if op != pairs[ch]:
                errors.append(f"{path}:{line} {ch} 与第 {ol} 行的 {op} 不匹配")
                return
    for op, ol in stack:
        errors.append(f"{path} 第 {ol} 行的 {op} 没有闭合")


DECL_RE = re.compile(r"\b(object|class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)")
FUN_RE = re.compile(r"\b(?:fun|val|var|const val)\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)")
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.M)


def block_body(code, brace_pos):
    depth = 0
    for i in range(brace_pos, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[brace_pos + 1:i]
    return code[brace_pos + 1:]


def collect_decls():
    members = {}
    fq = {}
    for path in kt_files():
        raw = open(path, encoding="utf-8").read()
        pkg = PACKAGE_RE.search(raw)
        pkg = pkg.group(1) if pkg else ""
        code = strip_code(raw)
        for m in DECL_RE.finditer(code):
            name = m.group(2)
            fq[f"{pkg}.{name}" if pkg else name] = path
            brace = code.find("{", m.end())
            if brace < 0:
                continue
            body = block_body(code, brace)
            found = {fm.group(1) for fm in FUN_RE.finditer(body)}
            members.setdefault(name, set()).update(found)
        # 文件级顶层声明：`import dev.aigw.core.util.str` 这种也要能认出来
        depth = 0
        for i, ch in enumerate(code):
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
            elif depth == 0 and code.startswith("fun ", i):
                tm = re.match(r"fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.<>?]*\.)?([A-Za-z_][A-Za-z0-9_]*)", code[i:])
                if tm:
                    fq[f"{pkg}.{tm.group(1)}" if pkg else tm.group(1)] = path
            elif depth == 0 and (code.startswith("val ", i) or code.startswith("var ", i)):
                tm = re.match(r"(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)", code[i:])
                if tm:
                    fq[f"{pkg}.{tm.group(1)}" if pkg else tm.group(1)] = path
    return members, fq


def check_refs(members, fq):
    for path in kt_files():
        raw = open(path, encoding="utf-8").read()
        code = strip_code(raw)
        for m in re.finditer(r"(?<![\w.])([A-Z][A-Za-z0-9_]*)\s*\.\s*([a-zA-Z_][A-Za-z0-9_]*)\s*\(", code):
            typ, member = m.group(1), m.group(2)
            if typ not in fq or typ not in members:
                continue
            if member in members[typ]:
                continue
            line = code[:m.start()].count("\n") + 1
            warnings.append(
                f"{os.path.relpath(path, ROOT)}:{line} {typ}.{member}() —— {typ} 内没有该成员（若是继承/扩展可忽略）"
            )


def check_cross_package_usage(fq):
    """跨包使用但没 import 的顶层声明。

    Kotlin 同包声明不需要 import；跨包才需要。之前 MainActivity 用了
    `UpdateScreen`（ui 包）却没 import，编译期才炸 —— 这一项就是抓这种。
    只对**可唯一确定归属**的名字报错：同名声明分散在多个包里时跳过，避免误报。
    """
    by_simple = {}
    for name, path in fq.items():
        simple = name.rsplit(".", 1)[-1]
        by_simple.setdefault(simple, []).append(name)

    for path in kt_files():
        raw = open(path, encoding="utf-8").read()
        code = strip_code(raw)
        pkg_match = PACKAGE_RE.search(raw)
        pkg = pkg_match.group(1) if pkg_match else ""
        imported = set(re.findall(r"^import\s+([\w.]+)", raw, re.M))
        imported_simple = {n.rsplit(".", 1)[-1] for n in imported}
        for simple, fqns in by_simple.items():
            if len(fqns) != 1:
                continue
            # 只查类型名（大写开头）：顶层函数/属性的同名变量极容易误报
            if not simple[:1].isupper():
                continue
            fqn = fqns[0]
            owner_pkg = fqn.rsplit(".", 1)[0] if "." in fqn else ""
            if owner_pkg == pkg:
                continue  # 同包，无需 import
            if simple in imported_simple:
                continue
            if not re.search(r"(?<![\w.])" + re.escape(simple) + r"(?![\w])", code):
                continue
            # 本文件自己声明了同名类型则豁免
            if re.search(r"\b(?:object|class|interface|fun|val)\s+" + re.escape(simple) + r"\b", code):
                continue
            errors.append(
                f"{os.path.relpath(path, ROOT)} 用到 {fqn}，但缺少 import"
            )


def check_imports(fq):
    for path in kt_files():
        raw = open(path, encoding="utf-8").read()
        for m in re.finditer(r"^import\s+(dev\.aigw\.[\w.]+)", raw, re.M):
            name = m.group(1)
            if name in fq or name.rsplit(".", 1)[0] in fq:
                continue
            if name.endswith("Kt") and name[:-2] in fq:
                continue
            warnings.append(f"{os.path.relpath(path, ROOT)} 导入 {name}，未找到声明（文件级顶层函数可忽略）")


def check_empty_bodies(path, raw):
    code = strip_code(raw)
    for m in re.finditer(r"\b(object|class)\s+([A-Za-z_][A-Za-z0-9_]*)[^\n{]*\{", code):
        brace = m.end() - 1
        body = block_body(code, brace).strip()
        if not body:
            errors.append(f"{os.path.relpath(path, ROOT)} {m.group(1)} {m.group(2)} 是空的（成员被误删？）")


def check_dup_fun(path, raw):
    code = strip_code(raw)
    seen = {}
    for m in re.finditer(r"^[ \t]*(?:private |internal |public |override |suspend )*fun\s+([A-Za-z_][A-Za-z0-9_]*)", code, re.M):
        seen.setdefault(m.group(1), []).append(code[:m.start()].count("\n") + 1)
    for name, lines in seen.items():
        if len(lines) > 1:
            warnings.append(f"{os.path.relpath(path, ROOT)} fun {name} 定义 {len(lines)} 次（行 {lines}，重载则忽略）")


def main():
    files = list(kt_files())
    if not files:
        errors.append("找不到 Kotlin 源文件")
    members, fq = collect_decls()
    for path in files:
        raw = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, ROOT)
        check_balance(rel, strip_code(raw))
        check_empty_bodies(path, raw)
        check_dup_fun(path, raw)
    check_refs(members, fq)
    check_imports(fq)
    check_cross_package_usage(fq)

    for w in warnings:
        print("WARN ", w)
    for e in errors:
        print("ERROR", e)
    print(f"\n扫描 {len(files)} 个文件，错误 {len(errors)}，警告 {len(warnings)}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
