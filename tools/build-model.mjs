#!/usr/bin/env node
// Matn korpusidan Alifbo tahmin modelini (ALM2 formati) yasaydi. Faqat Node.js kerak, paket yo'q.
// 1-2 o'tishlar matnni oqim bilan o'qiydi (GB'lab matnga yetadi); 2-o'tishda so'z raqamlari vaqtinchalik
// faylga yoziladi va yuqori tartibli (3-5 so'zli) o'tishlar o'sha fayldan tez o'qiydi.
//
//   node tools/build-model.mjs --lang uz --in a.txt b.txt --out app/src/main/assets/lm_uz.bin --max-order 5
//
// --lang       uz (yangi lotin) | uzc (o'zbek kirill) | en | ru
// --in         bir yoki bir nechta matn fayli (har qatorda gap(lar)); --leipzig bo'lsa "raqam<TAB>gap"
// --out        chiqish fayli
// --max-vocab 60000  --min-count 1  --min-bigram 1  --max-lines 0
// --max-order  2..5 (2 = faqat oldingi bitta so'z)      --ctx-min 5 (kontekst kamida necha marta uchrasin)
// --keep3 120000 --keep4 60000 --keep5 30000 (har tartibda saqlanadigan kontekstlar)  --k-high 4
// --damp tom,mary  (korpusda haddan tashqari ko'p uchraydigan so'zlarni 20 marta susaytirish)
//
// ALM2 formati (kichik-endian):
//   "ALM2" | u32 V | V x { u8 len, utf8, u8 uniQ } | V x { u8 n, n x { u16 id, u8 q } }      (1 so'zli kontekst)
//   | u8 maxOrder | har n = 3..maxOrder uchun: u32 C, C x { (n-1) x u16 id, u8 m, m x { u16 id, u8 q } }
//   so'zlar String.compareTo (UTF-16) tartibida, 0-o'rinda "<s>" (gap boshi);
//   kontekstlar id'lar ketma-ketligi bo'yicha o'sish tartibida (ikkilik qidiruv uchun).
//   q = min(255, round(log2(count + 1) * 10)).
import fs from 'node:fs';
import readline from 'node:readline';
import { cyrillicToLatin, latinToCyrillic, oldToNewLatin, scriptOf } from './translit.mjs';

const START = '<s>';
const TUTUQ = 'ʼ';
const K = 8;

const args = process.argv.slice(2);
function opt(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : def;
}
const lang = opt('lang');
const out = opt('out');
const maxVocab = Math.min(65000, Number(opt('max-vocab', '60000')));
const minUni = Number(opt('min-count', '1'));
const minBi = Number(opt('min-bigram', '1'));
const maxLines = Number(opt('max-lines', '0'));
const maxOrder = Math.min(5, Math.max(2, Number(opt('max-order', '2'))));
const ctxMin = Number(opt('ctx-min', '5'));
const kHigh = Number(opt('k-high', '4'));
const keepCap = { 3: Number(opt('keep3', '120000')), 4: Number(opt('keep4', '60000')), 5: Number(opt('keep5', '30000')) };
const candCap = Number(opt('cand-cap', '600000'));
const leipzig = args.includes('--leipzig');
const damp = new Set(String(opt('damp', '')).split(',').map((s) => s.trim()).filter(Boolean));
const dampF = (w) => (damp.has(w) ? 0.05 : 1);
const inputs = [];
{
  const i = args.indexOf('--in');
  if (i >= 0) for (let j = i + 1; j < args.length && !args[j].startsWith('--'); j++) inputs.push(args[j]);
}
if (!['uz', 'uzc', 'en', 'ru'].includes(lang) || !out || inputs.length === 0) {
  console.error('Foydalanish: node tools/build-model.mjs --lang uz|uzc|en|ru --in fayl... --out chiqish.bin');
  process.exit(2);
}

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
const JUNK = /(?:https?:\/\/|www\.)\S+|\S+@\S+\.\S+|[@#][\p{L}\p{N}_]+|t\.me\/\S+/giu;

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
 * Barcha fayllardagi gap bo'laklarini oqim bilan beradi: { ts: so'zlar, start: gap boshimi }.
 * Raqam kontekstni uzadi, lekin gap boshi emas ("5 ta", "2024 yil" dagi "ta", "yil" gap boshlamaydi);
 * ilovadagi ContextParser ham raqamdan keyin oldingi so'zni noma'lum deb oladi.
 */
async function* sentences() {
  let n = 0;
  for (const file of inputs) {
    const rl = readline.createInterface({ input: fs.createReadStream(file, { encoding: 'utf8' }), crlfDelay: Infinity });
    for await (let line of rl) {
      if (maxLines > 0 && ++n > maxLines) {
        rl.close();
        return;
      }
      if (leipzig) line = line.replace(/^\d+\t/, '');
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
  }
}

const t0 = Date.now();
const log = (msg) => console.error(`${lang}: ${msg} (${Math.round((Date.now() - t0) / 1000)} s)`);

// ---------------------------------------------------------------- 1-o'tish: so'zlar
// (birinchi harf bo'yicha bo'lingan Map'lar — bitta Map 16M yozuvdan oshmasin)
const shards = new Map();
let sentenceCount = 0;
let tokenCount = 0;
for await (const { ts } of sentences()) {
  sentenceCount++;
  for (const t of ts) {
    let m = shards.get(t[0]);
    if (!m) shards.set(t[0], (m = new Map()));
    m.set(t, (m.get(t) ?? 0) + 1);
    tokenCount++;
  }
}
let distinct = 0;
for (const m of shards.values()) distinct += m.size;
log(`1-o'tish: ${sentenceCount} gap, ${tokenCount} so'z, ${distinct} xil`);

const candidates = [];
for (const m of shards.values()) for (const [w, c] of m) if (c >= minUni) candidates.push([w, c]);
const kept = candidates.sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)).slice(0, maxVocab - 1);
const countOf = new Map(kept);
const vocab = [START, ...kept.map(([w]) => w)].sort();
const idOf = new Map(vocab.map((w, i) => [w, i]));
if (vocab[0] !== START) throw new Error('"<s>" 0-o\'rinda bo\'lishi kerak');
const startId = 0;
const uniOf = (w) => (w === START ? 0 : Math.max(1, Math.round(countOf.get(w) * dampF(w))));
shards.clear();
candidates.length = 0;

// ---------------------------------------------------------------- 2-o'tish: juftliklar + raqamli nusxa
// Raqamli nusxa: 0xFFFF = gap boshi, 0xFFFE = raqamdan keyingi bo'lak, 0xFFFD = lug'atda yo'q so'z.
const SEG_START = 0xffff;
const SEG_CONT = 0xfffe;
const UNK = 0xfffd;
const idsFile = out + '.ids.tmp';
const fd = fs.openSync(idsFile, 'w');
const wbuf = new Uint16Array(1 << 20);
let wpos = 0;
function emit(v) {
  wbuf[wpos++] = v;
  if (wpos === wbuf.length) {
    fs.writeSync(fd, Buffer.from(wbuf.buffer, 0, wpos * 2));
    wpos = 0;
  }
}

const rows = new Array(vocab.length);
for await (const { ts, start } of sentences()) {
  emit(start ? SEG_START : SEG_CONT);
  let prev = start ? startId : -1;
  for (const t of ts) {
    const id = idOf.get(t);
    if (id === undefined) {
      emit(UNK);
      prev = -1; // lug'atda yo'q so'z kontekstni uzadi
      continue;
    }
    emit(id);
    if (prev >= 0) {
      let row = rows[prev];
      if (!row) rows[prev] = row = new Map();
      row.set(id, (row.get(id) ?? 0) + 1);
    }
    prev = id;
  }
}
if (wpos > 0) fs.writeSync(fd, Buffer.from(wbuf.buffer, 0, wpos * 2));
fs.closeSync(fd);
log("2-o'tish tugadi");

/** Raqamli nusxani o'qib, har so'zda (oynadagi oldingi so'zlar bilan) [visit] ni chaqiradi. */
function replay(visit) {
  const rfd = fs.openSync(idsFile, 'r');
  const buf = Buffer.alloc(1 << 22);
  const win = [];
  let leftover = null;
  for (;;) {
    const n = fs.readSync(rfd, buf, 0, buf.length, null);
    if (n <= 0) break;
    let chunk = buf.subarray(0, n);
    if (leftover) {
      chunk = Buffer.concat([leftover, chunk]);
      leftover = null;
    }
    const usable = chunk.length - (chunk.length % 2);
    if (usable < chunk.length) leftover = Buffer.from(chunk.subarray(usable));
    for (let p = 0; p < usable; p += 2) {
      const v = chunk.readUInt16LE(p);
      if (v === SEG_START) {
        win.length = 0;
        win.push(startId);
      } else if (v === SEG_CONT || v === UNK) {
        win.length = 0;
      } else {
        visit(win, v);
        win.push(v);
        if (win.length > 4) win.shift();
      }
    }
  }
  fs.closeSync(rfd);
}

const q = (count) => Math.min(255, Math.round(Math.log2(count + 1) * 10));
const cmpEntries = (a, b) => b[1] - a[1] || (vocab[a[0]] < vocab[b[0]] ? -1 : 1);

// 1 so'zli kontekst (bigram) yakuniy qatorlari
const bigramFinal = new Array(vocab.length);
for (let i = 0; i < vocab.length; i++) {
  const row = rows[i];
  bigramFinal[i] = row
    ? [...row.entries()]
        .filter(([, c]) => c >= minBi)
        .map(([id, c]) => [id, Math.max(1, Math.round(c * dampF(vocab[id])))])
        .sort(cmpEntries)
        .slice(0, K)
    : [];
}

// ---------------------------------------------------------------- yuqori tartiblar (3..maxOrder)
// Kontekst kalitlari: 2 so'z -> a*65536+b; 3 so'z -> a*2^32+b*65536+c; 4 so'z -> ichma-ich Map.
const P16 = 65536;
const P32 = 4294967296;
const key2 = (a, b) => a * P16 + b;
const key3 = (a, b, c) => a * P32 + b * P16 + c;

const finalByOrder = {}; // n -> Map(kontekst kaliti satri -> yozuvlar)
const storedByOrder = {}; // n -> [{ ctx, entries }]
let prevCounts = null; // oldingi tartibdagi kontekstlar: [{ ctx, counts }]

function lowerTop(n, ctx) {
  // ctx'ning bir so'z qisqa qo'shimchasi uchun eng yaxshi davomlar
  const suffix = ctx.slice(1);
  for (let m = n - 1; m >= 3; m--) {
    const s = suffix.slice(suffix.length - (m - 1));
    const e = finalByOrder[m]?.get(s.join(','));
    if (e) return e;
  }
  return bigramFinal[suffix[suffix.length - 1]];
}

for (let n = 3; n <= maxOrder; n++) {
  // nomzod kontekstlar: oldingi tartibda kamida ctxMin marta uchragan (n-1) so'zli ketma-ketliklar
  let cand = [];
  if (n === 3) {
    for (let a = 0; a < vocab.length; a++) {
      const row = rows[a];
      if (row) for (const [b, c] of row) if (c >= ctxMin) cand.push([[a, b], c]);
    }
  } else {
    for (const { ctx, counts } of prevCounts) for (const [c, cnt] of counts) if (cnt >= ctxMin) cand.push([[...ctx, c], cnt]);
  }
  cand.sort((x, y) => y[1] - x[1]);
  if (cand.length > candCap) cand = cand.slice(0, candCap);

  const ctxs = cand.map(([ctx]) => ({ ctx, counts: new Map() }));
  cand = null;
  // kontekstni tez topish uchun indeks
  const idx2 = new Map();
  const idx3 = new Map();
  const idx4 = new Map();
  ctxs.forEach((e, i) => {
    const c = e.ctx;
    if (n === 3) idx2.set(key2(c[0], c[1]), i);
    else if (n === 4) idx3.set(key3(c[0], c[1], c[2]), i);
    else {
      const k = key3(c[0], c[1], c[2]);
      let inner = idx4.get(k);
      if (!inner) idx4.set(k, (inner = new Map()));
      inner.set(c[3], i);
    }
  });

  replay((win, id) => {
    const L = win.length;
    if (L < n - 1) return;
    let i;
    if (n === 3) i = idx2.get(key2(win[L - 2], win[L - 1]));
    else if (n === 4) i = idx3.get(key3(win[L - 3], win[L - 2], win[L - 1]));
    else i = idx4.get(key3(win[L - 4], win[L - 3], win[L - 2]))?.get(win[L - 1]);
    if (i === undefined) return;
    const m = ctxs[i].counts;
    m.set(id, (m.get(id) ?? 0) + 1);
  });

  // saqlash: eng yaxshi davomlar; quyi tartib bilan bir xil bo'lsa (ortiqcha) tashlanadi
  const stored = [];
  let redundant = 0;
  for (const { ctx, counts } of ctxs) {
    let total = 0;
    for (const c of counts.values()) total += c;
    const entries = [...counts.entries()]
      .filter(([, c]) => c >= minBi)
      .map(([id, c]) => [id, Math.max(1, Math.round(c * dampF(vocab[id])))])
      .sort(cmpEntries)
      .slice(0, kHigh);
    if (entries.length === 0) continue;
    const lower = lowerTop(n, ctx);
    const cmpLen = Math.min(3, entries.length);
    if (lower && lower.length >= cmpLen && entries.slice(0, cmpLen).every((e, j) => lower[j][0] === e[0])) {
      redundant++;
      continue;
    }
    stored.push({ ctx, entries, total });
  }
  stored.sort((x, y) => y.total - x.total);
  const keep = stored.slice(0, keepCap[n]);
  keep.sort((x, y) => {
    for (let j = 0; j < x.ctx.length; j++) if (x.ctx[j] !== y.ctx[j]) return x.ctx[j] - y.ctx[j];
    return 0;
  });
  finalByOrder[n] = new Map(keep.map((e) => [e.ctx.join(','), e.entries]));
  storedByOrder[n] = keep;
  prevCounts = ctxs;
  log(`${n} so'zli: ${ctxs.length} nomzod, ${stored.length} foydali (${redundant} ortiqcha), ${keep.length} saqlandi`);
}

// ---------------------------------------------------------------- yozish
const parts = [Buffer.from('ALM2', 'ascii')];
const u32 = (v) => {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(v);
  return b;
};
parts.push(u32(vocab.length));
for (const w of vocab) {
  const b = Buffer.from(w, 'utf8');
  if (b.length > 255) throw new Error('so\'z juda uzun: ' + w);
  parts.push(Buffer.from([b.length]), b, Buffer.from([w === START ? 0 : q(uniOf(w))]));
}
let bigramEntries = 0;
for (let i = 0; i < vocab.length; i++) {
  const entries = bigramFinal[i];
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
  const keep = storedByOrder[n] ?? [];
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
const data = Buffer.concat(parts);
fs.writeFileSync(out, data);
fs.rmSync(idsFile, { force: true });
console.log(
  `${lang}: ${sentenceCount} gap, ${tokenCount} so'z, lug'at ${vocab.length}, ${bigramEntries} juftlik` +
    (higherInfo.length ? `, kontekstlar ${higherInfo.join(' ')}` : '') +
    `, ${data.length} bayt -> ${out}`,
);
