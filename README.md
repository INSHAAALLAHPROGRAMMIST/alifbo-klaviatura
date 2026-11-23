# Söz — yangi özbek alifbosi uçun aqlli Android klaviatura

Tizim klaviaturasi (`InputMethodService`): Telegram'da, brauzerda, SMS'da — har qanday ilovada işlaydi.
Ilova nomi «Söz»; ichki identifikator (`uz.alifbo.klaviatura`) va repo nomi özgarmagan, şuning uçun
yangi versiyalar avvalgisining ustidan örnatilaveradi.

## Söz+ (`toliq` branch)

Asosiy «Söz» internetsiz qoladi. Bu branchdagi «Söz+» — alohida ilova (`uz.alifbo.klaviatura.toliq`),
telefonga «Söz» bilan yonma-yon örnatiladi va ikki narsa qöşadi:

- **Tarjimon (to'ğridan-to'ğri, Tor'siz).** Qatordagi «A文» klaviatura içida tarjima qatorini oçadi:
  tugmalar ilovaga emas, şu qatorga yozadi; ➤ — tarjima, «Qöyiş» — maydonga qöyiş, 📋 — buferdagi matnni
  tarjima. Xizmat — Google Cloud Translation (oyiga 500 ming belgigaça bepul); ilovaga CI'da qotirilgan
  standart kalit bilan işlaydi, sozlamadan özingiznikini kiritişingiz ham mumkin. Yangi alifbodagi matn
  xizmatga eski imloda (oʻ, sh…) yuboriladi, natija yangi alifboga (yoki kirillga) qaytariladi.
  2026-11: avval Tor (Orbot) orqali edi — Google Tor çiqiş tugunlarini bloklagani uçun olib taşlandi;
  endi xizmat söroq manzilingizni (IP) körişi mumkin.
- **Parollar ombori.** Kiriş maydonidan çiqilganda login (email / telefon / foydalanuvçi nomi) va parol
  şifrlab eslab qolinadi; keyingi safar qatorda 🔑 — telefon qulfi bilan tasdiqlab töldiriladi. Zaif parol
  yozilsa yoki yangi parol maydonida kuçli parol taklif qilinadi. Bosh parol bilan şifrlangan zaxira nusxa.

Sizib çiqişga qarşi:

- Tarmoq kodi faqat `Net.kt` da (CI boşqa faylda `java.net`/`javax.net` paydo bölsa yiğişni töxtatadi).
- `network_security_config.xml`: faqat tarjima manziliga TLS mumkin, boşqa hamma manzil uçun işonçli
  sertifikat yöq, şifrlanmagan HTTP taqiqlangan.
- Söroqda faqat matn va kalit: cookie, User-Agent, qurilma yoki ilova belgisi yöq.
- Standart kalit manbada yöq, faqat CI'dagi GitHub sirida (`GOOGLE_TRANSLATE_KEY`); şunga qaramay repo
  PUBLIC bölgani uçun APK'dan çiqarib olinişi mumkin — kalit Google Cloud Console'da Android ilova
  (package + SHA-1) bilan çeklangan va kvotaga ega.
- Parol maydonlarida tarjima öçiq; parollar ombori tarmoq kodiga ulanmagan.
- Ombor: AndroidKeyStore'dagi RSA-2048 (maxfiy kalit faqat telefon qulfi tasdiqlangandan keyin 5 daqiqa
  işlaydi) + AES-256-GCM. Qulfli paytda yangi parol oçiq kalit bilan şifrlab qö'yiladi. «Bu ilovada parol
  bormi» degan körsatkiç ham HMAC bilan: ilova nomi ham, parol ham oçiq yozilmaydi. Ekran öçganda yoki
  15 daqiqadan keyin ombor yana qulflanadi; ombor oçiq paytda klaviatura oynasi ekran rasmidan yaşiriladi.
- CI ruxsatlarni tekşiradi: faqat `INTERNET` va `USE_BIOMETRIC`.

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
### Tezkor maket (özbek tiliga optimallaştirilgan)

Sozlamalarda QWERTY örniga tanlanadi. `tools/optimize-layout.mjs` hisoblagan: harf va harf
juftlari çastotasi 113 mln sözli özbekça modeldan, yoziş vaqti ikki baş barmoqli Fitts modelidan
(bitta barmoq ketma-ket bossa masofaga qarab sekinroq, navbatlaşsa tez), keyin millionlab
joylaşuv ichidan eng tezi topildi (bir necha boşlang'ich nuqtadan bir xil natija):

```
ʼ x p f e │ z m q v h
 j o u ç  │ y d l s k
   a g i  │ b n r t
```

Unlilar chap, undoşlar ö'ng baş barmoqda: barmoq navbatlaşuvi 80% (QWERTY'da 50%), model bölyiça
bir harfga 172 ms (QWERTY'da 259 ms), ya'ni ~34% tez. Bu nazariy baho; amalda könikiş uçun
bir necha hafta kerak.

```
node tools/optimize-layout.mjs app/src/main/assets/lm_uz.bin --iters 600000 --restarts 8 --seed 7
```

### Bir qöl maketi (faqat ö'ng baş barmoq)

Xuddi şu usul, lekin hamma bosiş bitta barmoqda: navbatlaşuv yöq, har söz oxirida probelga borib
qaytiladi, ö'ng baş barmoq uçun chap va yuqori çetlar noqulayroq (`--mode one`):

```
ʼ f v e g d t y p x
 j ç u r a l o q z
  h k m n i s b
```

Tez-tez keladigan harflar markazda yonma-yon: ketma-ket ikki harf orasidagi o'rtaça masofa 3,8 dan
2,2 tugmaga tuşadi, model bölyiça bir bosişga 352 ms (QWERTY'da 425 ms), ya'ni ~17% tez.
Tezkor maket bir qölda faqat ~4% yaxşi bölgani uçun alohida maket qilindi.

```
node tools/optimize-layout.mjs app/src/main/assets/lm_uz.bin --mode one --iters 1000000 --restarts 8 --seed 99
```

Har qanday tugmani yuqoriga sürish — uning birinçi qö'şimça belgisi (yaşirin harf yoki raqam).
Probelni yonga sürish yoki globusni bosiş — tilni almaştiradi; globus içida emoji ham bor:
globusni yuqoriga sürish — emoji paneli, uzoq bosiş — emoji va til tanlagiç.

## Bufer (nusxalar tarixi)

Nusxa olingan har bir matn telefonda saqlanadi (`files/clipboard.txt`): eng yangisi tepada,
eskilari turaveradi (qadalmaganlardan oxirgi 100 tasi, qadalganlari çeksiz). Tahmin qatoridagi 📋
tugmasi panelni oçadi: bosiş — yoziladi, ☆ — qadab qö'yiş, ✕ — öçiriş, «Tozalaş» — qadalmaganlarni
öçiriş. Yangi nusxa 15 daqiqa davomida maydonga yozişdan oldin qatorda taklif qilinadi.
Parol menejerlari `IS_SENSITIVE` deb belgilagan nusxalar saqlanmaydi, parol maydonlarida taklif yöq.

## Tarjima

Klaviaturaning özi internetga ulanmaydi (INTERNET ruxsati yöq). «A文» tugmasi belgilangan matnni
(yoki maydondagi butun matnni) Android'ning `PROCESS_TEXT` yöli bilan telefondagi tarjima ilovasiga
(avvalo Google Tarjimon, keyin Yandex, DeepL) uzatadi; ilova tarjimani qaytarsa, matn örniga
qö'yiladi. Qaytarmasa, tarjimani nusxalash kifoya — bufer uni ilib oladi va qatorda taklif qiladi.

## Tuzatiş

Yozilgan söz lug'atda bölmasa, tuzatilgan şakli tahmin qatorining örtasida çiqadi. Yangi alifbo
harflariga oid xatolar (`sh → ş`, `oʼ/o'/o\` → ö`, `gʼ → ğ`, `s → ş`, `o → ö`, `g → ğ`) eng yuqori
ustunlikda, keyin oddiy xatolar (bitta harf tuşgan, ortiqça, almaşgan yoki örni almaşgan).
Avtomatik tuzatiş yoniq bölsa, probel yoki tinish belgisi bosilganda söz almaştiriladi
(tuzatiş urğu rangida körsatiladi); darhol orqaga öçiriş — asl söz qaytadi va endi tuzatilmaydi.

## Tiniş, probel va katta harf

- Söz ortidan `. , ! ? ; : …` qöyilsa, probel özi qöyiladi; ortidan yana probel bosilsa, ikkinçisi
  qöşilmaydi. Raqamlarda (`3.14`, `1,5`, `10:30`), email va havolalarda, `h.k.` kabi qisqartmalarda
  probel qöyilmaydi.
- Tinişdan keyin yana tiniş yoki yopuvçi belgi bosilsa, probel uning ortiga ötadi: `Nima?!`, `Hmm...`,
  `«Salom.»`. Söz ortidan probel bosilib, darhol `. , ! ?` bosilsa, belgi probel oldiga köçadi
  (`söz ,` → `söz, `); taklif tanlangaçdagi probel ham şunday.
- Probel tez ikki marta bosilsa — nuqta va probel. Enter bosilsa, oxirdagi avtomatik probel olinadi.
- Gap tugagaç (`. ! ?` va probel), yangi qatorda va maydon boşida birinçi harf katta. Ilova bu bayroqni
  (`CAP_SENTENCES`) unutgan köp qatorli maydonlarda ham; bir qatorli bayroqsiz maydonlarda (login, qidiruv) kiçik.
- Belgilar sahifasida (`?123`) belgi yozilib, probel yoki Enter bosilsa — harflarga qaytiladi.
- Inglizçada yolğiz `i` (va `i'm`, `i'll`, `i've`, `i'd`) avtomatik tuzatişda `I` bölib qöyiladi.
- Hammasi sozlamalarning «Yoziş» bölimida öçiriladi. Mantiq `Spacing.kt` da, testlari `SpacingTest.kt` da.

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
- Özbek (lotin va kirill) modellari Raqamli texnologiyalar vazirligining
  [Özbek tili korpusi](https://huggingface.co/datasets/Digitaluz/uzbek-language-corpus) dan
  (ODC-By 1.0; 5,6 mln hujjat, ~6 mlrd söz) yasalgan. Bitta server buni 6 soatda ulgurmaydi, şuning uçun
  `.github/workflows/corpus.yml` 136 faylni 34 serverda parallel sanaydi (`tools/count-shard.mjs`),
  `tools/merge-model.mjs` esa sanoqlarni birlaştirib model yozadi. Kirill modeli korpusning haqiqiy
  kirill hujjatlaridan quriladi (quyidagi so'zlaşuv bölagi esa unga lotindan transliteratsiya qilinadi).
- Korpus rasmiy matnga og'ir (kutubxona, arxiv, qonunlar; Telegram atigi ~1,6%), şuning uçun
  `.github/workflows/corpus-mix.yml` unga so'zlaşuv manbalarini qöşadi:
  [UzCrawl](https://huggingface.co/datasets/tahrirchi/uz-crawl) Telegram bloglari (Apache-2.0) va
  [UzBooks](https://huggingface.co/datasets/tahrirchi/uz-books) badiiy kitoblari (Apache-2.0; `lat` ning
  birinçi 4 fayli, `--fiction` filtri). Bu bölak ×32 og'irlik oladi (8, 16, 32 sinalgan;
  "xayrli tong", "tuğilgan kun" kabi iboralar 32 da eng yaxşi). Sanoqlar yozişdan oldin ~1e8 sözli
  korpus şkalasiga keltiriladi (`--norm`): ilovadagi ball qoidalari şunga sozlangan.
  Özbekça harflarsiz yozilgan xato şakllar lug'atdan çiqariladi (`--confusion-ratio 22`): kirill
  klaviaturasidan (`булган`, `kup`, `xozir`) va apostrofsiz lotindan (`bolgan`, `togri`). Aks holda ilova
  ularni ma'lum söz deb tuzatmasdi yoki "bulgan" ni "bölgan" emas, "bulğan" qilardi.
- Inglizça va ruscha modellar — [Tatoeba](https://tatoeba.org) (CC BY 2.0 FR),
  `.github/workflows/models.yml`. Hamma modellar artefakt bölib çiqadi, tekşirilgaç
  `app/src/main/assets/` ga qö'şiladi. `tools/seed/` — kiçik boşlang'ich matnlar.
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
3. Sayqal: so'z tuzatish ✔, qö'sh probel → nuqta ✔, tinişdan keyin probel ✔, vaqtinçalik buferlar ✔
