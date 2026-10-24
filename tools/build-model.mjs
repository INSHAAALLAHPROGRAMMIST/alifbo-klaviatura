#!/usr/bin/env node
// Matn korpusidan Alifbo tahmin modelini (ALM1 formati) yasaydi. Faqat Node.js kerak, paket yo'q.
// Fayllar oqim bilan ikki marta o'qiladi (1: so'zlar, 2: juftliklar), shuning uchun GB'lab matnga ham yetadi.
//
//   node tools/build-model.mjs --lang uz --in a.txt b.txt --out app/src/main/assets/lm_uz.bin
//
// --lang   uz (yangi lotin) | uzc (o'zbek kirill) | en | ru
// --in     bir yoki bir nechta matn fayli (har qatorda gap(lar)); --leipzig bo'lsa "raqam<TAB>gap"
// --out    chiqish fayli
// --max-vocab 60000   --min-count 1   --min-bigram 1   --max-lines 0 (0 = cheklanmagan)
//
// Format (kichik-endian):
//   "ALM1" | u32 V | V x { u8 len, utf8 bayt, u8 uniQ } | V x { u8 n, n x { u16 id, u8 q } }
//   so'zlar String.compareTo (UTF-16) tartibida; 0-o'rinda "<s>" (gap boshi).
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
const maxVocab = Math.min(65535, Number(opt('max-vocab', '60000')));
const minUni = Number(opt('min-count', '1'));
const minBi = Number(opt('min-bigram', '1'));
const maxLines = Number(opt('max-lines', '0'));
const leipzig = args.includes('--leipzig');
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

/** Barcha fayllardagi gaplarni (so'zlar ro'yxati sifatida) oqim bilan beradi. */
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
      // gaplar: . ! ? ... va raqamlar kontekstni uzadi
      for (const sent of line.split(/[.!?…\d]+/)) {
        const ts = tokens(sent);
        if (ts.length > 0) yield ts;
      }
    }
  }
}

// 1-o'tish: so'zlar (birinchi harf bo'yicha bo'lingan Map'lar — bitta Map 16M yozuvdan oshmasin)
const shards = new Map();
let sentenceCount = 0;
let tokenCount = 0;
for await (const ts of sentences()) {
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
console.error(`${lang}: 1-o'tish: ${sentenceCount} gap, ${tokenCount} so'z, ${distinct} xil`);

const candidates = [];
for (const m of shards.values()) for (const [w, c] of m) if (c >= minUni) candidates.push([w, c]);
const kept = candidates
  .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
  .slice(0, maxVocab - 1);
const countOf = new Map(kept);
const vocab = [START, ...kept.map(([w]) => w)].sort();
const idOf = new Map(vocab.map((w, i) => [w, i]));
if (vocab[0] !== START) throw new Error('"<s>" 0-o\'rinda bo\'lishi kerak');
const uniOf = (w) => (w === START ? 0 : countOf.get(w));
shards.clear();
candidates.length = 0;

// 2-o'tish: juftliklar (har oldingi so'z uchun alohida Map — bitta Map 16M yozuvdan oshmasin)
const rows = new Array(vocab.length);
const startId = idOf.get(START);
for await (const ts of sentences()) {
  let prev = startId;
  for (const t of ts) {
    const id = idOf.get(t);
    if (id === undefined) {
      prev = -1; // lug'atda yo'q so'z kontekstni uzadi
      continue;
    }
    if (prev >= 0) {
      let row = rows[prev];
      if (!row) rows[prev] = row = new Map();
      row.set(id, (row.get(id) ?? 0) + 1);
    }
    prev = id;
  }
}

const q = (count) => Math.min(255, Math.round(Math.log2(count + 1) * 10));

const parts = [Buffer.from('ALM1', 'ascii')];
const u32 = Buffer.alloc(4);
u32.writeUInt32LE(vocab.length);
parts.push(u32);
for (const w of vocab) {
  const b = Buffer.from(w, 'utf8');
  if (b.length > 255) throw new Error('so\'z juda uzun: ' + w);
  parts.push(Buffer.from([b.length]), b, Buffer.from([w === START ? 0 : q(uniOf(w))]));
}
let bigramEntries = 0;
for (let i = 0; i < vocab.length; i++) {
  const row = rows[i];
  const entries = row
    ? [...row.entries()]
        .filter(([, c]) => c >= minBi)
        .sort((a, b) => b[1] - a[1] || (vocab[a[0]] < vocab[b[0]] ? -1 : 1))
        .slice(0, K)
    : [];
  parts.push(Buffer.from([entries.length]));
  for (const [id, c] of entries) {
    const e = Buffer.alloc(3);
    e.writeUInt16LE(id, 0);
    e.writeUInt8(q(c), 2);
    parts.push(e);
    bigramEntries++;
  }
}
const data = Buffer.concat(parts);
fs.writeFileSync(out, data);
console.log(`${lang}: ${sentenceCount} gap, ${tokenCount} so'z, lug'at ${vocab.length}, ${bigramEntries} juftlik, ${data.length} bayt -> ${out}`);
