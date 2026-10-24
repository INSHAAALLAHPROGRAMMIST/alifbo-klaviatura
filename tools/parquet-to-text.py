# Parquet faylidagi "text" ustunini oddiy matn fayliga yozadi (har yozuv yangi qatordan).
# Faqat CI'da ishlatiladi:
#   python3 tools/parquet-to-text.py kirish.parquet chiqish.txt            # hammasi
#   python3 tools/parquet-to-text.py kirish.parquet chiqish.txt --fiction  # faqat badiiy kitoblar (UzBooks)
#
# --fiction: UzBooks'da janr belgisi yo'q, shuning uchun kitob matnidan taxmin qilinadi:
#   badiiy  = dialog qatorlari ("— ...") yoki "dedi", "kuldi" kabi so'zlar ko'p, yoki she'r (qisqa qatorlar);
#   darslik = "mavzu", "jadval", "formula", "tadqiqot" kabi so'zlar yoki raqamlar ko'p.
#   Kitob qatorlari (OCR) gap o'rtasida uzilgani uchun xatboshi ichidagi qatorlar birlashtiriladi.
import re
import sys

import pyarrow.parquet as pq

args = sys.argv[1:]
fiction = "--fiction" in args
paths = [a for a in args if not a.startswith("--")]
src, dst = paths[0], paths[1]

SPEECH = re.compile(
    r"\b(dedi|dedim|deding|dedik|dedilar|deydi|deyishdi|kuldi|kulib|jilmaydi|jilmayib|"
    r"pichirladi|qichqirdi|so.?radi|xo.?rsindi|yig.?ladi|qaradi|tikildi)\b"
)
ACADEMIC = re.compile(
    r"\b(mavzu|mavzusi|jadval|jadvali|formula|misol|mashq|metodika|metodik|tadqiqot|xulosa|"
    r"dissertatsiya|darslik|kafedra|fakultet|topshiriq|ilmiy|referat|ma.?ruza|bob)\b"
)
DIALOG = re.compile(r"^\s*[—–-]\s*\S", re.M)
DIGIT = re.compile(r"\d")


def classify(t):
    low = t.lower()
    words = len(low.split())
    if words < 3000:
        return False, "qisqa"
    per_k = 1000.0 / words
    speech = len(SPEECH.findall(low)) * per_k
    acad = len(ACADEMIC.findall(low)) * per_k
    dialog = len(DIALOG.findall(t)) * per_k
    digits = len(DIGIT.findall(t)) / max(1, len(t))
    lines = [ln for ln in t.split("\n") if ln.strip()]
    avg = words / max(1, len(lines))
    # she'r: qatorlari qisqa (3-8 so'z); lug'at va ro'yxatlarda qator 1-2 so'zdan iborat bo'ladi
    poetry = len(lines) > 300 and 3 <= avg < 8
    # "— ..." qatorlari lug'at va ro'yxatlarda ham ko'p, shuning uchun dialog yolg'iz o'zi yetmaydi
    ok = digits < 0.01 and acad < 1.0 and (speech >= 0.8 or (dialog >= 2.0 and speech >= 0.3) or poetry)
    info = f"nutq={speech:.2f} ilmiy={acad:.2f} dialog={dialog:.2f} raqam={digits:.3f} qator={avg:.1f}"
    return ok, info


def unwrap(t):
    t = t.replace("\r", "\n")
    t = re.sub(r"(\w)-\n(\w)", r"\1\2", t)  # qator oxiridagi bo'g'in ko'chirish
    t = re.sub(r"(?<!\n)\n(?!\n)", " ", t)  # xatboshi ichidagi qatorlar
    return re.sub(r"\n{2,}", "\n", t)


rows = kept = 0
words_kept = 0
samples = {True: [], False: []}
with open(dst, "w", encoding="utf-8") as out:
    pf = pq.ParquetFile(src)
    for batch in pf.iter_batches(columns=["text"], batch_size=200 if fiction else 2000):
        for t in batch.column(0).to_pylist():
            if not t:
                continue
            rows += 1
            if fiction:
                ok, info = classify(t)
                if len(samples[ok]) < 6:
                    head = re.sub(r"\s+", " ", t[:90])
                    samples[ok].append(f"  {head!r}  [{info}]")
                if not ok:
                    continue
                t = unwrap(t)
                words_kept += len(t.split())
            else:
                t = t.replace("\r", "\n")
            kept += 1
            out.write(t)
            out.write("\n")

if fiction:
    print(f"{src}: {rows} kitob, {kept} tasi badiiy deb olindi ({words_kept} so'z) -> {dst}")
    print("olingan namunalar:")
    print("\n".join(samples[True]))
    print("tashlangan namunalar:")
    print("\n".join(samples[False]))
else:
    print(f"{src}: {rows} yozuv -> {dst}")
