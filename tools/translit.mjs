// O'zbek yozuvlari orasida o'tkazish (faqat model qurishda ishlatiladi, ilovada emas).
// Barcha funksiyalar KICHIK HARFLI matn kutadi.

const OLD_APOS = "['‘’ʻʼ`´]";

/** Matn asosan kirillmi yoki lotinmi. */
export function scriptOf(s) {
  let cyr = 0;
  let lat = 0;
  for (const ch of s) {
    if (/\p{Script=Cyrillic}/u.test(ch)) cyr++;
    else if (/\p{Script=Latin}/u.test(ch)) lat++;
  }
  return cyr > lat ? 'cyrl' : 'latn';
}

/** Eski lotin imlosi (o', g', sh, ch) -> yangi (ö, ğ, ş, ç). */
export function oldToNewLatin(s) {
  return s
    .replace(new RegExp('o' + OLD_APOS, 'g'), 'ö')
    .replace(new RegExp('g' + OLD_APOS, 'g'), 'ğ')
    .replace(/sh/g, 'ş')
    .replace(/ch/g, 'ç');
}

const L2C = {
  a: 'а', b: 'б', d: 'д', f: 'ф', g: 'г', h: 'ҳ', i: 'и',
  j: 'ж', k: 'к', l: 'л', m: 'м', n: 'н', o: 'о', p: 'п',
  q: 'қ', r: 'р', s: 'с', t: 'т', u: 'у', v: 'в', x: 'х',
  z: 'з', 'ö': 'ў', 'ğ': 'ғ', 'ş': 'ш', 'ç': 'ч',
  'ʼ': 'ъ',
};

const LAT_VOWELS = new Set(['a', 'e', 'i', 'o', 'u', 'ö']);

/** Yangi lotin -> o'zbek kirill (taxminiy, bashorat uchun yetarli). */
export function latinToCyrillic(s) {
  const chars = [...s];
  let out = '';
  for (let i = 0; i < chars.length; i++) {
    const c = chars[i];
    const n = chars[i + 1];
    const prev = chars[i - 1];
    if (c === 'y') {
      if (n === 'a') { out += 'я'; i++; continue; }
      if (n === 'o') { out += 'ё'; i++; continue; }
      if (n === 'u') { out += 'ю'; i++; continue; }
      if (n === 'e') { out += 'е'; i++; continue; }
      out += 'й';
      continue;
    }
    if (c === 'e') {
      const atStart = prev === undefined || !/\p{L}/u.test(prev);
      out += atStart ? 'э' : 'е';
      continue;
    }
    out += L2C[c] ?? c;
  }
  return out;
}

const C2L = {
  'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'ж': 'j',
  'з': 'z', 'и': 'i', 'й': 'y', 'к': 'k', 'л': 'l', 'м': 'm',
  'н': 'n', 'о': 'o', 'п': 'p', 'р': 'r', 'с': 's', 'т': 't',
  'у': 'u', 'ф': 'f', 'х': 'x', 'ц': 'ts', 'ч': 'ç',
  'ш': 'ş', 'щ': 'şç', 'ъ': 'ʼ', 'ы': 'i', 'ь': '',
  'э': 'e', 'ю': 'yu', 'я': 'ya', 'ё': 'yo', 'ў': 'ö',
  'қ': 'q', 'ғ': 'ğ', 'ҳ': 'h',
};

/** O'zbek kirill -> yangi lotin. */
export function cyrillicToLatin(s) {
  const chars = [...s];
  let out = '';
  for (let i = 0; i < chars.length; i++) {
    const c = chars[i];
    const prev = chars[i - 1];
    if (c === 'е') {
      const atStart = prev === undefined || !/\p{L}/u.test(prev);
      const afterVowel = prev !== undefined && (LAT_VOWELS.has(C2L[prev] ?? '') || prev === 'ъ' || prev === 'ь');
      out += atStart || afterVowel ? 'ye' : 'e';
      continue;
    }
    out += C2L[c] ?? c;
  }
  return out;
}
