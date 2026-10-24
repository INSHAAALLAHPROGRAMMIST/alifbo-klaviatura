# Parquet faylidagi "text" ustunini oddiy matn fayliga yozadi (har yozuv yangi qatordan).
# Faqat CI'da ishlatiladi:  python3 tools/parquet-to-text.py kirish.parquet chiqish.txt
import sys

import pyarrow.parquet as pq

src, dst = sys.argv[1], sys.argv[2]
rows = 0
with open(dst, "w", encoding="utf-8") as out:
    pf = pq.ParquetFile(src)
    for batch in pf.iter_batches(columns=["text"], batch_size=2000):
        for t in batch.column(0).to_pylist():
            if t:
                out.write(t.replace("\r", "\n"))
                out.write("\n")
                rows += 1
print(f"{src}: {rows} yozuv -> {dst}")
