#!/usr/bin/env node
// ALM1 modelini mustaqil o'qib tekshiradi (Kotlin o'quvchisidan alohida amalga oshirilgan).
//   node tools/query-model.mjs model.bin --next men      # "men" dan keyingi so'zlar
//   node tools/query-model.mjs model.bin --prefix yax    # prefiks bo'yicha eng ko'p uchraydiganlar
//   node tools/query-model.mjs model.bin --check         # tartib va butunlikni tekshirish
import fs from 'node:fs';

const file = process.argv[2];
const args = process.argv.slice(3);
const buf = fs.readFileSync(file);
if (buf.toString('ascii', 0, 4) !== 'ALM1') throw new Error('ALM1 emas');
let p = 4;
const V = buf.readUInt32LE(p);
p += 4;
const words = [];
const uniQ = [];
for (let i = 0; i < V; i++) {
  const len = buf[p++];
  words.push(buf.toString('utf8', p, p + len));
  p += len;
  uniQ.push(buf[p++]);
}
const rows = [];
for (let i = 0; i < V; i++) {
  const n = buf[p++];
  const row = [];
  for (let k = 0; k < n; k++) {
    row.push({ id: buf.readUInt16LE(p), q: buf[p + 2] });
    p += 3;
  }
  rows.push(row);
}
if (p !== buf.length) throw new Error(`fayl oxirida ${buf.length - p} ortiqcha bayt`);

function opt(name) {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : undefined;
}

if (args.includes('--check')) {
  for (let i = 1; i < V; i++) if (!(words[i - 1] < words[i])) throw new Error(`tartib buzilgan: ${words[i - 1]} / ${words[i]}`);
  if (words[0] !== '<s>') throw new Error('0-o\'rin <s> emas');
  console.log(`OK: ${V} so'z, tartib to'g'ri, bayt balansi to'g'ri`);
}
const next = opt('next');
if (next !== undefined) {
  const i = words.indexOf(next);
  if (i < 0) console.log(`"${next}" lug'atda yo'q`);
  else console.log(rows[i].map((e) => `${words[e.id]}(${e.q})`).join('  '));
}
const prefix = opt('prefix');
if (prefix !== undefined) {
  const hits = words.map((w, i) => ({ w, q: uniQ[i] })).filter((x) => x.w.startsWith(prefix) && x.w !== '<s>');
  hits.sort((a, b) => b.q - a.q || (a.w < b.w ? -1 : 1));
  console.log(hits.slice(0, 10).map((x) => `${x.w}(${x.q})`).join('  '));
}
