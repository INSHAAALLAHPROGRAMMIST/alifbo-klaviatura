# Söz — yangi özbek alifbosi uçun aqlli Android klaviatura

Tizim klaviaturasi (`InputMethodService`): Telegram'da, brauzerda, SMS'da — har qanday ilovada işlaydi.
Ilova nomi «Söz»; ichki identifikator (`uz.alifbo.klaviatura`) va repo nomi özgarmagan, şuning uçun
yangi versiyalar avvalgisining ustidan örnatilaveradi.

## Alifbo

Maket 2026-yil 21-oktyabrda imzolangan **O'RQ-1184-son** qonundagi alifboga asoslangan:
28 harf + tutuq belgisi. `Oʻ Gʻ Sh Ch` → `Ö Ğ Ş Ç`; `ng` mustaqil harf emas (n + g).

```
A B D E F G Ğ H I J K L M N O Ö P Q R S Ş Ç T U V X Y Z   +  ʼ (tutuq belgisi, U+02BC)
```

QWERTY joylashuvi to'liq saqlangan (10 / 9 / 7 tugma): `w` örnida tutuq belgisi `ʼ`, `c` örnida `ç`;
`ş`, `ğ`, `ö` esa `s`, `g`, `o` tugmalari içida — burçakda kiçik harf bölib körinadi, tugmani
yuqoriga sürib yoki uzoq bosib yoziladi.
Katta harf `Locale.ROOT` bilan: `i → I` (turkcha `İ` emas).

## Maketlar

| Til | Qatorlar |
|---|---|
| Özbek (yangi alifbo) | `q ʼ e r t y u i o p` / `a s d f g h j k l` / `z x ç v b n m` (+ `ş ğ ö` içida) |
| English (US) | QWERTY, uzoq bosganda aksentlar |
| Русский | ЙЦУКЕН (`ё` — `е` ustida, `ъ` — `ь` ustida) |
| Ўзбек (кирилл) | ЙЦУКЕН + `ў қ ғ ҳ`, `ъ` alohida tugma |

Hamma tillarda: yuqori qatordagi harfni uzoq bosiş — raqam; `?123` va `=\<` sahifalarida
`! " # $ % & ' ( ) * + , - . / : ; < = > ? @ [ \ ] ^ _ ` { | } ~` va `« » “ ” – — € £ ¥ ₽ ₸ № °`.
Har qanday tugmani yuqoriga sürish — uning birinçi qö'şimça belgisi (yaşirin harf yoki raqam).
Probelni yonga sürish yoki globusni bosiş — tilni almaştiradi; globus içida emoji ham bor:
globusni yuqoriga sürish — emoji paneli, uzoq bosiş — emoji va til tanlagiç.

## Tuzatiş

Yozilgan söz lug'atda bölmasa, tuzatilgan şakli tahmin qatorining örtasida çiqadi. Yangi alifbo
harflariga oid xatolar (`sh → ş`, `oʼ/o'/o\` → ö`, `gʼ → ğ`, `s → ş`, `o → ö`, `g → ğ`) eng yuqori
ustunlikda, keyin oddiy xatolar (bitta harf tuşgan, ortiqça, almaşgan yoki örni almaşgan).
Avtomatik tuzatiş yoniq bölsa, probel yoki tinish belgisi bosilganda söz almaştiriladi
(tuzatiş urğu rangida körsatiladi); darhol orqaga öçiriş — asl söz qaytadi va endi tuzatilmaydi.

## Xavfsizlik

- `AndroidManifest.xml`'da **heç qanday** `uses-permission` yöq (INTERNET ham). Tayyor APK'da faqat
  androidx qö'shadigan ichki `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` çiqadi: u ilovaning özi uçun,
  foydalanuvchidan heç narsa söramaydi va tarmoqqa aloqasi yöq.
  CI har yig'işda `aapt2 dump permissions` bilan tekşiradi va INTERNET çiqsa to'xtaydi.
- `allowBackup=false`; sozlamalar faqat ilovaning özida (SharedPreferences).

## Söz tahmini

Klaviatura tepasidagi qatorda 3 ta taklif: eng yaxşisi o'rtada. Yozilayotgan sözni tugatadi,
söz tugagaç keyingisini taklif qiladi. Hammasi oflayn:

- `app/src/main/assets/lm_<til>.bin` — tayyor model (ALM2): lug'at, chastota, keyingi söz jadvali
  va 2–4 oldingi sözli kontekst jadvallari (uzunroq mos kelgan ibora ustun turadi; quyi tartib bilan
  bir xil natija beradigan kontekstlar saqlanmaydi).
  `tools/build-model.mjs` matn korpusidan yasaydi, `tools/query-model.mjs` mustaqil tekşiradi.
  Özbek kirill modeli lotin matndan transliteratsiya orqali yasaladi (`tools/translit.mjs`).
- Katta modellar GitHub Actions'da (`.github/workflows/models.yml`, qö'lda işga tuşiriladi) yasaladi:
  özbek — [UzCrawl](https://huggingface.co/datasets/tahrirchi/uz-crawl) (Apache-2.0; Telegram bloglari +
  `news-00002`) va [UzBooks](https://huggingface.co/datasets/tahrirchi/uz-books) (Apache-2.0; `lat` ning
  birinçi 4 fayli, `--fiction` filtri bilan faqat badiiy kitoblar va şeirlar, ~64 mln söz),
  inglizça va ruscha — [Tatoeba](https://tatoeba.org) (CC BY 2.0 FR). Natija artefakt bölib
  çiqadi, tekşirilgaç `app/src/main/assets/` ga qö'şiladi. `tools/seed/` — kiçik boşlang'ich matnlar.
- Eski yozuvdan yordam (sozlamada öçiriladi): yangi alifboga könikkunça `s` yozilsa `ş` bilan
  boşlanadigan sözlar ham taklif qilinadi (`o → ö`, `g → ğ`), eski odatdagi `sh`, `oʼ`, `gʼ` esa
  `ş`, `ö`, `ğ` deb tuşuniladi. Almaştirilgan har harf uçun kiçik jarima bor.
- Shaxsiy örganiş: siz yozgan sözlar `files/personal_<til>.txt` da, faqat telefonda.
  Parol, email, havola va "taklifsiz" maydonlarda tahmin ham, örganiş ham öçiq.

```
node tools/build-model.mjs --lang uz  --in tools/seed/uz.txt --out app/src/main/assets/lm_uz.bin
node tools/build-model.mjs --lang uzc --in tools/seed/uz.txt --out app/src/main/assets/lm_uzc.bin
node tools/query-model.mjs app/src/main/assets/lm_uz.bin --check --next men --prefix yax
```

## Emoji

`androidx.emoji2:emoji2-emojipicker` (rasmiy, Apache 2.0): toifalar, so'nggilar, teri ranglari.
Rasmlar telefonning özidagi emoji şrifti bilan çiziladi. Emoji Unicode belgisi sifatida kiritiladi,
şuning uçun Telegram'da çatda ko'rinişi Telegram'ning özi tanlagan emoji to'plamiga bog'liq.

## Qurişi

Kompyuterga heç nima örnatilmaydi: GitHub Actions (`.github/workflows/build.yml`) testlarni işlatadi,
imzolangan APK yig'adi va artefakt sifatida beradi. Imzo kaliti repoda emas, GitHub sirlarida
(`KEYSTORE_B64`, `KEYSTORE_PASSWORD`); nusxasi kompyuterda `klaviatura-kalit` papkasida.

## Bosqiçlar

1. Asos: 4 maket, belgilar, emoji, tema, sozlamalar ✔ (shu versiya)
2. Tahmin: oflayn lug'at, keyingi söz, shaxsiy örganiş (faqat qurilmada)
3. Sayqal: so'z tuzatish, qö'sh probel → nuqta, vaqtinçalik buferlar
