#!/usr/bin/env node
// Matn korpusidan Alifbo tahmin modelini (ALM1 formati) yasaydi. Faqat Node.js kerak, paket yo'q.
//
//   node tools/build-model.mjs --lang uz --in a.txt b.txt --out app/src/main/assets/lm_uz.bin
//
// --lang   uz (yangi lotin) | uzc (o'zbek kirill) | en | ru
// --in     bir yoki bir nechta matn fayli (har qatorda gap(lar)); --leipzig bo'lsa "raqam<TAB>gap"
// --out    chiqish fayli
// --max-vocab 60000   --min-count 1   --min-bigram 1
//
// Format (kichik-endian):
//   "ALM1" | u32 V | V x { u8 len, utf8 bayt, u8 uniQ } | V x { u8 n, n x { u16 id, u8 q } }
//   so'zlar String.compareTo (UTF-16) tartibida; 0-o'rinda "<s>" (gap boshi).
//   q = min(255, round(log2(count + 1) * 10)).
import fs from 'node:fs';
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
const maxVocab = Number(opt('max-vocab', '60000'));
const minUni = Number(opt('min-count', '1'));
const minBi = Number(opt('min-bigram', '1'));
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

function prepare(sentence) {
  let s = sentence.normalize('NFC').toLowerCase();
  if (lang === 'uz' || lang === 'uzc') {
    if (scriptOf(s) === 'cyrl') {
      if (lang === 'uz') s = cyrillicToLatin(s);
    } else {
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
  const re = lang === 'en' ? /[\p{L}']+/gu : /[\p{L}ʼ]+/gu;
  const res = [];
  for (let t of s.match(re) ?? []) {
    t = t.replace(/^['ʼ]+|['ʼ]+$/g, '');
    if (t.length >= 1 && t.length <= 32 && ALLOWED[lang].test(t)) res.push(t);
  }
  return res;
}

const uni = new Map();
const big = new Map();
function bump(map, key) {
  map.set(key, (map.get(key) ?? 0) + 1);
}

let sentenceCount = 0;
for (const file of inputs) {
  const text = fs.readFileSync(file, 'utf8');
  for (let line of text.split(/\r?\n/)) {
    if (leipzig) line = line.replace(/^\d+\t/, '');
    // gaplar: . ! ? ... va raqamlar kontekstni uzadi
    for (const sent of line.split(/[.!?…\d]+/)) {
      const ts = tokens(sent);
      if (ts.length === 0) continue;
      sentenceCount++;
      let prev = START;
      for (const t of ts) {
        bump(uni, t);
        let row = big.get(prev);
        if (!row) big.set(prev, (row = new Map()));
        bump(row, t);
        prev = t;
      }
    }
  }
}

const kept = [...uni.entries()]
  .filter(([, c]) => c >= minUni)
  .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
  .slice(0, maxVocab - 1)
  .map(([w]) => w);
const vocab = [START, ...kept].sort();
const idOf = new Map(vocab.map((w, i) => [w, i]));
if (vocab[0] !== START) throw new Error('"<s>" 0-o\'rinda bo\'lishi kerak');

const q = (count) => Math.min(255, Math.round(Math.log2(count + 1) * 10));

const parts = [Buffer.from('ALM1', 'ascii')];
const u32 = Buffer.alloc(4);
u32.writeUInt32LE(vocab.length);
parts.push(u32);
for (const w of vocab) {
  const b = Buffer.from(w, 'utf8');
  if (b.length > 255) throw new Error('so\'z juda uzun: ' + w);
  parts.push(Buffer.from([b.length]), b, Buffer.from([w === START ? 0 : q(uni.get(w))]));
}
let bigramEntries = 0;
for (const w of vocab) {
  const row = big.get(w);
  const entries = row
    ? [...row.entries()]
        .filter(([n, c]) => c >= minBi && idOf.has(n))
        .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1))
        .slice(0, K)
    : [];
  parts.push(Buffer.from([entries.length]));
  for (const [n, c] of entries) {
    const e = Buffer.alloc(3);
    e.writeUInt16LE(idOf.get(n), 0);
    e.writeUInt8(q(c), 2);
    parts.push(e);
    bigramEntries++;
  }
}
const data = Buffer.concat(parts);
fs.writeFileSync(out, data);
console.log(`${lang}: ${sentenceCount} gap, ${vocab.length} so'z, ${bigramEntries} bigram, ${data.length} bayt -> ${out}`);
