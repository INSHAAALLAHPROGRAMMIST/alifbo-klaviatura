# Alifbo — yangi özbek alifbosi uçun Android klaviatura

Tizim klaviaturasi (`InputMethodService`): Telegram'da, brauzerda, SMS'da — har qanday ilovada işlaydi.

## Alifbo

Maket 2026-yil 21-oktyabrda imzolangan **O'RQ-1184-son** qonundagi alifboga asoslangan:
28 harf + tutuq belgisi. `Oʻ Gʻ Sh Ch` → `Ö Ğ Ş Ç`; `ng` mustaqil harf emas (n + g).

```
A B D E F G Ğ H I J K L M N O Ö P Q R S Ş Ç T U V X Y Z   +  ʼ (tutuq belgisi, U+02BC)
```

`w` va `c` yöq, şuning uçun `ğ` — `w` o'rnida, `ç` — `c` o'rnida; `ş` va `ö` qatorlar oxirida.
Katta harf `Locale.ROOT` bilan: `i → I` (turkcha `İ` emas).

## Maketlar

| Til | Qatorlar |
|---|---|
| Özbek (yangi alifbo) | `q ğ e r t y u i o p` / `a s d f g h j k l ş` / `z x ç v b n m ö` |
| English (US) | QWERTY, uzoq bosganda aksentlar |
| Русский | ЙЦУКЕН (`ё` — `е` ustida, `ъ` — `ь` ustida) |
| Ўзбек (кирилл) | ЙЦУКЕН + `ў қ ғ ҳ`, `ъ` alohida tugma |

Hamma tillarda: yuqori qatordagi harfni uzoq bosiş — raqam; `?123` va `=\<` sahifalarida
`! " # $ % & ' ( ) * + , - . / : ; < = > ? @ [ \ ] ^ _ ` { | } ~` va `« » “ ” – — € £ ¥ ₽ ₸ № °`.
Probelni yonga surişş — tilni almaştiradi; glob tugmasini uzoq bosiş — til tanlagiç.

## Xavfsizlik

- `AndroidManifest.xml`'da **hec qanday** `uses-permission` yöq (INTERNET ham). Tayyor APK'da faqat
  androidx qö'shadigan ichki `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` çiqadi: u ilovaning özi uçun,
  foydalanuvchidan hec narsa so'ramaydi va tarmoqqa aloqasi yöq.
  CI har yig'işda `aapt2 dump permissions` bilan tekşiradi va INTERNET çiqsa to'xtaydi.
- `allowBackup=false`; sozlamalar faqat ilovaning özida (SharedPreferences).

## Söz tahmini

Klaviatura tepasidagi qatorda 3 ta taklif: eng yaxşisi o'rtada. Yozilayotgan sözni tugatadi,
söz tugagaç keyingisini taklif qiladi. Hammasi oflayn:

- `app/src/main/assets/lm_<til>.bin` — tayyor model (ALM1): lug'at, chastota, keyingi söz jadvali.
  `tools/build-model.mjs` matn korpusidan yasaydi, `tools/query-model.mjs` mustaqil tekşiradi.
  Özbek kirill modeli lotin matndan transliteratsiya orqali yasaladi (`tools/translit.mjs`).
- Hozirgi modellar `tools/seed/` dagi kiçik boşlang'ich matnlardan; katta korpus keyingi bosqiç.
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

Kompyuterga hec nima örnatilmaydi: GitHub Actions (`.github/workflows/build.yml`) testlarni işlatadi,
imzolangan APK yig'adi va artefakt sifatida beradi. Imzo kaliti repoda emas, GitHub sirlarida
(`KEYSTORE_B64`, `KEYSTORE_PASSWORD`); nusxasi kompyuterda `klaviatura-kalit` papkasida.

## Bosqiçlar

1. Asos: 4 maket, belgilar, emoji, tema, sozlamalar ✔ (shu versiya)
2. Tahmin: oflayn lug'at, keyingi söz, shaxsiy örganiş (faqat qurilmada)
3. Sayqal: so'z tuzatish, qö'sh probel → nuqta, vaqtinçalik buferlar
