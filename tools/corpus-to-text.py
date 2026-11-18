# Vazirlik korpusining (Digitaluz/uzbek-language-corpus) bitta parquet faylini sanoq uchun matnga aylantiradi.
# Faqat CI'da ishlatiladi (.github/workflows/corpus.yml):
#   python3 tools/corpus-to-text.py kirish.parquet uz.txt uzc.txt
#
# Har qator: "vazn<TAB>matn" (fayllar oxiriga qo'shiladi).
#   uz.txt  — hamma hujjatlar (kirill matnni count-shard.mjs lotinga o'giradi);
#   uzc.txt — faqat kirill yozuvidagi hujjatlar (o'zbek kirill modeli haqiqiy kirill matndan quriladi).
# Shaxsiy ma'lumot belgilari ([NAME], [PHONE] ...) "0" ga almashtiriladi: so'z bo'lib kirmaydi va
# raqam kabi kontekstni uzadi.
import re
import sys
from collections import Counter

import pyarrow.parquet as pq

# Manba vazni: kitob va arxivlar so'z soni bo'yicha ustun (rasmiy va eski uslub ko'p), shuning uchun
# zamonaviy yozma va so'zlashuv tili (Telegram, veb, badiiy-ma'rifiy matnlar) ko'proq og'irlik oladi.
WEIGHT = {"Telegram": 4, "Web": 2, "Wikipedia": 2, "Tahrirchi": 2, "Ziyonet/Ziyouz": 2}
PLACEHOLDER = re.compile(r"\[[A-Z_]{2,24}\]")


def unwrap(t):
    t = re.sub(r"(\w)-\n(\w)", r"\1\2", t)  # qator oxiridagi bo'g'in ko'chirish
    t = re.sub(r"(?<!\n)\n(?!\n)", " ", t)  # xatboshi ichidagi qatorlar (skanerlangan sahifalar)
    return re.sub(r"\n{2,}", "\n", t)


src, uz_path, uzc_path = sys.argv[1:4]
docs = Counter()
words = Counter()
with open(uz_path, "a", encoding="utf-8") as uz, open(uzc_path, "a", encoding="utf-8") as uzc:
    pf = pq.ParquetFile(src)
    for batch in pf.iter_batches(columns=["text", "source", "script", "format_origin", "word_count"], batch_size=1000):
        cols = batch.to_pydict()
        for text, source, script, origin, wc in zip(
            cols["text"], cols["source"], cols["script"], cols["format_origin"], cols["word_count"]
        ):
            if not text:
                continue
            w = WEIGHT.get(source, 1)
            t = PLACEHOLDER.sub(" 0 ", text.replace("\r", "\n"))
            if origin == "OCR":
                t = unwrap(t)
            body = "".join(f"{w}\t{ln}\n" for ln in t.split("\n") if ln.strip())
            uz.write(body)
            if script == "Cyrillic":
                uzc.write(body)
            docs[(source, script)] += 1
            words[(source, script)] += wc or 0

print(f"{src}:")
for key in sorted(docs):
    source, script = key
    print(f"  {source} / {script}: {docs[key]} hujjat, {words[key]} so'z, vazn {WEIGHT.get(source, 1)}")
