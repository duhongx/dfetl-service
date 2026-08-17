from pathlib import Path

path = Path("web/app/etl/app-shell-final.tsx")
text = path.read_text(encoding="utf-8")
old = '} 个标准字段","按合同","按合同","只读"]] : [];'
new = '} 个标准字段`,"按合同","按合同","只读"]] : [];'
count = text.count(old)
if count != 1:
    raise RuntimeError(f"expected one malformed dataset template, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
