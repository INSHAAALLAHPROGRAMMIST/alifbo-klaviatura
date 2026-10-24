#!/usr/bin/env node
// ALM1/ALM2 modelini mustaqil o'qib tekshiradi (Kotlin o'quvchisidan alohida amalga oshirilgan).
//   node tools/query-model.mjs model.bin --next men          # "men" dan keyingi so'zlar
//   node tools/query-model.mjs model.bin --ctx "men bugun"   # 2+ so'zli kontekstdan keyingi so'zlar
//   node tools/query-model.mjs model.bin --prefix yax        # prefiks bo'yicha eng ko'p uchraydiganlar
//   node tools/query-model.mjs model.bin --check             # tartib va butunlikni tekshirish
import fs from 'node:fs';

const file = process.argv[2];
const args = process.argv.slice(3);
const buf = fs.readFileSync(file);
const magic = buf.toString('ascii', 0, 4);
if (magic !== 'ALM1' && magic !== 'ALM2') throw new Error('ALM1/ALM2 emas');
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
let maxOrder = 2;
const higher = {}; // n -> [{ ctx: [ids], row: [{id,q}] }]
if (magic === 'ALM2') {
  maxOrder = buf[p++];
  for (let n = 3; n <= maxOrder; n++) {
    const C = buf.readUInt32LE(p);
    p += 4;
    const list = [];
    for (let i = 0; i < C; i++) {
      const ctx = [];
      for (let j = 0; j < n - 1; j++) {
        ctx.push(buf.readUInt16LE(p));
        p += 2;
      }
      const m = buf[p++];
      const row = [];
      for (let k = 0; k < m; k++) {
        row.push({ id: buf.readUInt16LE(p), q: buf[p + 2] });
        p += 3;
      }
      list.push({ ctx, row });
    }
    higher[n] = list;
  }
}
if (p !== buf.length) throw new Error(`fayl oxirida ${buf.length - p} ortiqcha bayt`);

function opt(name) {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : undefined;
}
const fmt = (row) => row.map((e) => `${words[e.id]}(${e.q})`).join('  ');

if (args.includes('--check')) {
  for (let i = 1; i < V; i++) if (!(words[i - 1] < words[i])) throw new Error(`tartib buzilgan: ${words[i - 1]} / ${words[i]}`);
  if (words[0] !== '<s>') throw new Error('0-o\'rin <s> emas');
  for (let n = 3; n <= maxOrder; n++) {
    const list = higher[n];
    for (let i = 1; i < list.length; i++) {
      const a = list[i - 1].ctx;
      const b = list[i].ctx;
      let c = 0;
      for (let j = 0; j < a.length && c === 0; j++) c = a[j] - b[j];
      if (c >= 0) throw new Error(`${n}-tartib kontekstlari tartibsiz (${i})`);
    }
  }
  const sizes = Object.entries(higher).map(([n, l]) => `${n}:${l.length}`).join(' ');
  console.log(`OK: ${magic}, ${V} so'z, tartib ${maxOrder}${sizes ? ' (' + sizes + ')' : ''}, bayt balansi to'g'ri`);
}
const next = opt('next');
if (next !== undefined) {
  const i = words.indexOf(next);
  console.log(i < 0 ? `"${next}" lug'atda yo'q` : fmt(rows[i]));
}
const ctxArg = opt('ctx');
if (ctxArg !== undefined) {
  const ws = ctxArg.split(/\s+/).filter(Boolean);
  const ids = ws.map((w) => words.indexOf(w));
  if (ids.some((x) => x < 0)) console.log(`kontekstda lug'atda yo'q so'z bor: ${ctxArg}`);
  else if (ws.length === 1) console.log(fmt(rows[ids[0]]));
  else {
    const list = higher[ws.length + 1] ?? [];
    const hit = list.find((e) => e.ctx.every((x, j) => x === ids[j]));
    console.log(hit ? fmt(hit.row) : `"${ctxArg}" konteksti saqlanmagan (quyi tartibga tushiladi)`);
  }
}
const prefix = opt('prefix');
if (prefix !== undefined) {
  const hits = words.map((w, i) => ({ w, q: uniQ[i] })).filter((x) => x.w.startsWith(prefix) && x.w !== '<s>');
  hits.sort((a, b) => b.q - a.q || (a.w < b.w ? -1 : 1));
  console.log(hits.slice(0, 10).map((x) => `${x.w}(${x.q})`).join('  '));
}
