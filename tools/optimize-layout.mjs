#!/usr/bin/env node
// O'zbek tili uchun telefon klaviaturasi maketini optimallashtiradi (ikki bosh barmoq bilan yozish).
//
//   node tools/optimize-layout.mjs app/src/main/assets/lm_uz.bin [--iters 400000] [--restarts 12] [--seed 7]
//
// 1) Harf va harf juftlari chastotasi tayyor modeldagi so'zlardan olinadi (so'z soni ~ 2^(q/10) - 1).
//    ş, ğ, ö mos ravishda s, g, o tugmalari ichida bo'lgani uchun o'sha tugmalar hisobiga qo'shiladi.
// 2) Maket geometriyasi Söz klaviaturasiniki: 10 / 9 / 7 tugma (26 joy = 25 harf + tutuq belgisi).
// 3) Yozish vaqti modeli (Fitts qonuni): bitta barmoq ketma-ket ikki tugmani bossa
//    t = a + b * log2(D / W + 1); barmoqlar navbatlashsa t = T_ALT (ikkinchi barmoq oldindan tayyor turadi);
//    yuqori qatorlar biroz uzoqroq: har qatorga REACH qo'shiladi.
// 4) Simulyatsion "yumshatish" (simulated annealing) bilan eng kam o'rtacha vaqtli joylashuv qidiriladi.
import fs from 'node:fs';

const file = process.argv[2];
const args = process.argv.slice(3);
const opt = (n, d) => {
  const i = args.indexOf('--' + n);
  return i >= 0 ? Number(args[i + 1]) : d;
};
const ITERS = opt('iters', 400000);
const RESTARTS = opt('restarts', 12);
let seed = opt('seed', 7);
const rand = () => {
  // mulberry32 — qayta takrorlanadigan natija uchun
  seed |= 0;
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};

// ---------------------------------------------------------------- model so'zlari
const buf = fs.readFileSync(file);
let p = 4;
const V = buf.readUInt32LE(p);
p += 4;
const words = [];
for (let i = 0; i < V; i++) {
  const len = buf[p++];
  const w = buf.toString('utf8', p, p + len);
  p += len;
  const q = buf[p++];
  if (w !== '<s>') words.push([w, Math.pow(2, q / 10) - 1]);
}

const TUTUQ = 'ʼ';
const LETTERS = [...'abdefghijklmnopqrstuvxyzç', TUTUQ];
const MERGE = { 'ş': 's', 'ğ': 'g', 'ö': 'o' };
const idx = new Map(LETTERS.map((c, i) => [c, i]));
const N = LETTERS.length; // 26
const uni = new Float64Array(N);
const big = new Float64Array(N * N);
let total = 0;
for (const [w, c] of words) {
  let prev = -1;
  for (const ch0 of w) {
    const ch = MERGE[ch0] ?? ch0;
    const k = idx.get(ch);
    if (k === undefined) {
      prev = -1;
      continue;
    }
    uni[k] += c;
    total += c;
    if (prev >= 0) big[prev * N + k] += c;
    prev = k;
  }
}
for (let i = 0; i < N; i++) uni[i] /= total;
let bigTotal = 0;
for (let i = 0; i < N * N; i++) bigTotal += big[i];
for (let i = 0; i < N * N; i++) big[i] /= bigTotal;

// ---------------------------------------------------------------- geometriya va vaqt modeli
const ROW_H = 1.27; // tugma balandligi / kengligi (52dp / ~41dp)
const slots = [];
for (let i = 0; i < 10; i++) slots.push({ x: i + 0.5, y: 0, r: 0 });
for (let i = 0; i < 9; i++) slots.push({ x: i + 1.0, y: ROW_H, r: 1 });
for (let i = 0; i < 7; i++) slots.push({ x: i + 2.0, y: 2 * ROW_H, r: 2 });
const S = slots.length; // 26
const A = 0.1;
const B = 0.15;
const T_ALT = 0.13;
const REACH = 0.015;
const thumb = slots.map((s) => (s.x < 5 ? 0 : 1));
const pairT = new Float64Array(S * S);
for (let a = 0; a < S; a++) {
  for (let b = 0; b < S; b++) {
    if (thumb[a] !== thumb[b]) pairT[a * S + b] = T_ALT;
    else {
      const d = Math.hypot(slots[a].x - slots[b].x, slots[a].y - slots[b].y);
      pairT[a * S + b] = A + B * Math.log2(d + 1);
    }
  }
}
const reach = slots.map((s) => REACH * (2 - s.r));

/** pos[harf] = joy raqami. O'rtacha bir harfga ketadigan vaqt (soniya). */
function cost(pos) {
  let c = 0;
  for (let i = 0; i < N; i++) {
    c += uni[i] * reach[pos[i]];
    const row = i * N;
    const pi = pos[i] * S;
    for (let j = 0; j < N; j++) {
      const f = big[row + j];
      if (f) c += f * pairT[pi + pos[j]];
    }
  }
  return c;
}

function fromRows(rows) {
  const pos = new Int32Array(N);
  let s = 0;
  for (const row of rows) for (const ch of row) pos[idx.get(ch)] = s++;
  return pos;
}

function toRows(pos) {
  const at = new Array(S);
  for (let i = 0; i < N; i++) at[pos[i]] = LETTERS[i];
  return [at.slice(0, 10), at.slice(10, 19), at.slice(19, 26)];
}

const qwerty = [
  ['q', TUTUQ, 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
  ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'],
  ['z', 'x', 'ç', 'v', 'b', 'n', 'm'],
];
const base = cost(fromRows(qwerty));

// ---------------------------------------------------------------- qidiruv
let best = null;
let bestCost = Infinity;
for (let r = 0; r < RESTARTS; r++) {
  const pos = new Int32Array(N);
  const perm = [...Array(S).keys()];
  for (let i = S - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1));
    [perm[i], perm[j]] = [perm[j], perm[i]];
  }
  for (let i = 0; i < N; i++) pos[i] = perm[i];
  let cur = cost(pos);
  const T0 = 0.01;
  for (let it = 0; it < ITERS; it++) {
    const temp = T0 * (1 - it / ITERS) + 1e-6;
    const a = Math.floor(rand() * N);
    let b = Math.floor(rand() * N);
    if (a === b) continue;
    [pos[a], pos[b]] = [pos[b], pos[a]];
    const next = cost(pos);
    if (next <= cur || rand() < Math.exp((cur - next) / temp)) cur = next;
    else [pos[a], pos[b]] = [pos[b], pos[a]];
  }
  if (cur < bestCost) {
    bestCost = cur;
    best = Int32Array.from(pos);
  }
  console.error(`qayta boshlash ${r + 1}/${RESTARTS}: ${cur.toFixed(5)} s`);
}

const fmt = (rows) => rows.map((r) => r.join(' ')).join('  /  ');
const topUni = LETTERS.map((c, i) => [c, uni[i]]).sort((a, b) => b[1] - a[1]);
console.log('Eng ko\'p uchraydigan harflar (ş,ğ,ö — s,g,o ga qo\'shilgan):');
console.log('  ' + topUni.map(([c, f]) => `${c} ${(f * 100).toFixed(1)}%`).join('  '));
const alt = (pos) => {
  let s = 0;
  for (let i = 0; i < N; i++) for (let j = 0; j < N; j++) if (thumb[pos[i]] !== thumb[pos[j]]) s += big[i * N + j];
  return s;
};
const balance = (pos) => {
  let l = 0;
  for (let i = 0; i < N; i++) if (thumb[pos[i]] === 0) l += uni[i];
  return l;
};
console.log(`\nQWERTY (Söz):  ${fmt(qwerty)}`);
console.log(`  o'rtacha ${(base * 1000).toFixed(1)} ms/harf, barmoq navbatlashuvi ${(alt(fromRows(qwerty)) * 100).toFixed(0)}%, chap barmoq ${(balance(fromRows(qwerty)) * 100).toFixed(0)}%`);
console.log(`\nTezkor:        ${fmt(toRows(best))}`);
console.log(`  o'rtacha ${(bestCost * 1000).toFixed(1)} ms/harf, barmoq navbatlashuvi ${(alt(best) * 100).toFixed(0)}%, chap barmoq ${(balance(best) * 100).toFixed(0)}%`);
console.log(`\nModel bo'yicha farq: ${(((base - bestCost) / base) * 100).toFixed(1)}% tezroq`);
console.log(`JSON: ${JSON.stringify(toRows(best))}`);
