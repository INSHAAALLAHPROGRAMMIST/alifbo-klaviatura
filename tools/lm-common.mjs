// Tahmin modellari uchun umumiy qism: matnni so'zlarga ajratish va ALM2 faylini yozish.
// build-model.mjs (bitta serverda), count-shard.mjs va merge-model.mjs (taqsimlangan qurish) ishlatadi.
//
// ALM2 formati (kichik-endian):
//   "ALM2" | u32 V | V x { u8 len, utf8, u8 uniQ } | V x { u8 n, n x { u16 id, u8 q } }      (1 so'zli kontekst)
//   | u8 maxOrder | har n = 3..maxOrder uchun: u32 C, C x { (n-1) x u16 id, u8 m, m x { u16 id, u8 q } }
//   so'zlar String.compareTo (UTF-16) tartibida, 0-o'rinda "<s>" (gap boshi);
//   kontekstlar id'lar ketma-ketligi bo'yicha o'sish tartibida (ikkilik qidiruv uchun).
//   q = min(255, round(log2(count + 1) * 10)).
import { cyrillicToLatin, latinToCyrillic, oldToNewLatin, scriptOf } from './translit.mjs';

export const START = '<s>';
export const TUTUQ = 'ʼ';
/** 1 so'zli kontekstda saqlanadigan davomlar soni. */
export const K = 8;
export const LANGS = ['uz', 'uzc', 'en', 'ru'];

const ALLOWED = {
  uz: /^[abdefghijklmnopqrstuvxyzöğşçʼ]+$/u,
  en: /^[a-z]+(?:'[a-z]+)?$/u,
  ru: /^[а-яё]+$/u,
  uzc: /^[а-яёўқғҳ]+$/u,
};

// O'zbek korpusidagi inglizcha gaplarni ajratish uchun
const EN_STOP = new Set(['the', 'and', 'is', 'of', 'to', 'in', 'for', 'with', 'you', 'that', 'are', 'this', 'on', 'it']);
// O'zbek kirilliga xos harflar: ў қ ғ ҳ (ruscha gaplarni ajratish uchun)
const UZ_CYR = /[ўқғҳ]/u;
// Havola, @nom, #teg, email — so'z emas
export const JUNK = /(?:https?:\/\/|www\.)\S+|\S+@\S+\.\S+|[@#][\p{L}\p{N}_]+|t\.me\/\S+/giu;

export const q = (count) => Math.min(255, Math.round(Math.log2(count + 1) * 10));

/** [lang] tili uchun so'zlarga ajratgich. */
export function makeTokenizer(lang) {
  /** Gapni modelning yozuviga keltiradi; bu tilga tegishli bo'lmasa null. */
  function prepare(sentence) {
    let s = sentence.normalize('NFC').toLowerCase().replace(JUNK, ' ');
    if (lang === 'uz' || lang === 'uzc') {
      if (scriptOf(s) === 'cyrl') {
        if (!UZ_CYR.test(s)) return null; // ruscha bo'lsa kerak
        if (lang === 'uz') s = cyrillicToLatin(s);
      } else {
        const raw = s.match(/\p{L}+/gu) ?? [];
        let en = 0;
        for (const w of raw) if (EN_STOP.has(w)) en++;
        if (en >= 2) return null; // inglizcha gap
        s = oldToNewLatin(s).replace(/['‘’ʻ`´]/g, TUTUQ);
        if (lang === 'uzc') s = latinToCyrillic(s);
      }
    } else if (lang === 'en') {
      s = s.replace(/[’‘ʼ`´]/g, "'");
    }
    return s;
  }

  function tokens(sentence) {
    const s = prepare(sentence);
    if (s === null) return [];
    const re = lang === 'en' ? /[\p{L}']+/gu : /[\p{L}ʼ]+/gu;
    const res = [];
    for (let t of s.match(re) ?? []) {
      t = t.replace(/^['ʼ]+|['ʼ]+$/g, '');
      if (t.length >= 1 && t.length <= 32 && ALLOWED[lang].test(t)) res.push(t);
    }
    return res;
  }

  /**
   * Bir qatordagi gap bo'laklari: { ts: so'zlar, start: gap boshimi }.
   * Raqam kontekstni uzadi, lekin gap boshi emas ("5 ta", "2024 yil" dagi "ta", "yil" gap boshlamaydi);
   * ilovadagi ContextParser ham raqamdan keyin oldingi so'zni noma'lum deb oladi.
   */
  function* segments(line) {
    // havolalardagi nuqtalar gapni bo'lib yubormasligi uchun avval olib tashlanadi
    line = line.replace(JUNK, ' ');
    for (const sent of line.split(/[.!?…]+/)) {
      const segs = sent.split(/\d+/);
      for (let i = 0; i < segs.length; i++) {
        const ts = tokens(segs[i]);
        if (ts.length > 0) yield { ts, start: i === 0 };
      }
    }
  }

  return { tokens, segments };
}

/**
 * ALM2 faylining baytlari.
 * [vocab] — saralangan so'zlar (0-o'rinda START), [uniQ](i) — so'zning q qiymati,
 * [bigramRows][i] — [[id, son], ...], [higher][n] — [{ ctx: id'lar, entries: [[id, son], ...] }] (ctx bo'yicha saralangan).
 */
export function encodeAlm2(vocab, uniQ, bigramRows, higher, maxOrder) {
  const parts = [Buffer.from('ALM2', 'ascii')];
  const u32 = (v) => {
    const b = Buffer.alloc(4);
    b.writeUInt32LE(v);
    return b;
  };
  parts.push(u32(vocab.length));
  vocab.forEach((w, i) => {
    const b = Buffer.from(w, 'utf8');
    if (b.length > 255) throw new Error('so\'z juda uzun: ' + w);
    parts.push(Buffer.from([b.length]), b, Buffer.from([uniQ(i)]));
  });
  let bigramEntries = 0;
  for (let i = 0; i < vocab.length; i++) {
    const entries = bigramRows[i];
    parts.push(Buffer.from([entries.length]));
    for (const [id, c] of entries) {
      const e = Buffer.alloc(3);
      e.writeUInt16LE(id, 0);
      e.writeUInt8(q(c), 2);
      parts.push(e);
      bigramEntries++;
    }
  }
  parts.push(Buffer.from([maxOrder]));
  const higherInfo = [];
  for (let n = 3; n <= maxOrder; n++) {
    const keep = higher[n] ?? [];
    parts.push(u32(keep.length));
    for (const { ctx, entries } of keep) {
      const b = Buffer.alloc(ctx.length * 2 + 1 + entries.length * 3);
      let p = 0;
      for (const id of ctx) {
        b.writeUInt16LE(id, p);
        p += 2;
      }
      b.writeUInt8(entries.length, p++);
      for (const [id, c] of entries) {
        b.writeUInt16LE(id, p);
        b.writeUInt8(q(c), p + 2);
        p += 3;
      }
      parts.push(b);
    }
    higherInfo.push(`${n}:${keep.length}`);
  }
  return { data: Buffer.concat(parts), bigramEntries, higherInfo };
}
