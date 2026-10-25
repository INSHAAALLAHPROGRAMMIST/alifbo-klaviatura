#!/usr/bin/env node
// O'zbek tili uchun telefon klaviaturasi maketini optimallashtiradi.
//
//   node tools/optimize-layout.mjs app/src/main/assets/lm_uz.bin [--mode two|one] [--iters 400000] [--restarts 12] [--seed 7]
//
//   --mode two (sukut): ikki bosh barmoq bilan yozish ("Tezkor" maket).
//   --mode one        : faqat o'ng qo'l bosh barmog'i bilan yozish ("Bir qo'l" maket).
//
// 1) Harf va harf juftlari chastotasi tayyor modeldagi so'zlardan olinadi (so'z soni ~ 2^(q/10) - 1).
//    ş, ğ, ö mos ravishda s, g, o tugmalari ichida bo'lgani uchun o'sha tugmalar hisobiga qo'shiladi.
// 2) Maket geometriyasi Söz klaviaturasiniki: 10 / 9 / 7 tugma (26 joy = 25 harf + tutuq belgisi),
//    pastda probel (x 3.0 .. 7.9).
// 3) Yozish vaqti modeli (Fitts qonuni): bitta barmoq ketma-ket ikki tugmani bossa
//    t = a + b * log2(D / W + 1).
//    two: barmoqlar navbatlashsa t = T_ALT; yuqori qatorlar biroz uzoqroq (REACH).
//    one: hamma bosish bitta barmoqda; har so'z oxirida probelga borib qaytiladi; o'ng bosh barmoq
//         uchun chap va yuqori chetlar noqulayroq (o'ng-pastdagi tayanch nuqtadan masofa, REACH1).
// 4) Simulyatsion "yumshatish" (simulated annealing) bilan eng kam o'rtacha vaqtli joylashuv qidiriladi.
import fs from 'node:fs';

const file = process.argv[2];
const args = process.argv.slice(3);
const opt = (n, d) => {
  const i = args.indexOf('--' + n);
  return i >= 0 ? args[i + 1] : d;
};
const MODE = opt('mode', 'two');
const ITERS = Number(opt('iters', 400000));
const RESTARTS = Number(opt('restarts', 12));
let seed = Number(opt('seed', 7));
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
const first = new Float64Array(N); // so'z boshidagi harf (probeldan keyin)
const last = new Float64Array(N); // so'z oxiridagi harf (probeldan oldin)
let total = 0;
let wordTotal = 0;
for (const [w, c] of words) {
  let prev = -1;
  let firstK = -1;
  let lastK = -1;
  for (const ch0 of w) {
    const ch = MERGE[ch0] ?? ch0;
    const k = idx.get(ch);
    if (k === undefined) {
      prev = -1;
      continue;
    }
    if (firstK < 0) firstK = k;
    lastK = k;
    uni[k] += c;
    total += c;
    if (prev >= 0) big[prev * N + k] += c;
    prev = k;
  }
  if (firstK >= 0) {
    first[firstK] += c;
    last[lastK] += c;
    wordTotal += c;
  }
}
// "one" rejimi xom sonlar bilan ishlaydi (probel bosishlari ham hisobda), "two" — ulushlar bilan
const rawUni = Float64Array.from(uni);
const rawBig = Float64Array.from(big);
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
const REACH1 = 0.006;
const fitts = (d) => A + B * Math.log2(d + 1);
const thumb = slots.map((s) => (MODE === 'one' ? 1 : s.x < 5 ? 0 : 1));
const pairT = new Float64Array(S * S);
for (let a = 0; a < S; a++) {
  for (let b = 0; b < S; b++) {
    if (thumb[a] !== thumb[b]) pairT[a * S + b] = T_ALT;
    else pairT[a * S + b] = fitts(Math.hypot(slots[a].x - slots[b].x, slots[a].y - slots[b].y));
  }
}
// probel: x 3.0 .. 7.9, y = 3 * ROW_H; barmoq eng yaqin nuqtasiga boradi
const SPACE = { x0: 3.0, x1: 7.9, y: 3 * ROW_H };
const spaceT = slots.map((s) => {
  const dx = s.x < SPACE.x0 ? SPACE.x0 - s.x : s.x > SPACE.x1 ? s.x - SPACE.x1 : 0;
  return fitts(Math.hypot(dx, SPACE.y - s.y));
});
// o'ng bosh barmoq tayanchi: klaviaturaning o'ng-past qismi
const HOME = { x: 7.0, y: 1.5 * ROW_H };
const reach = slots.map((s) =>
  MODE === 'one' ? REACH1 * Math.hypot(s.x - HOME.x, s.y - HOME.y) : REACH * (2 - s.r),
);

/** pos[harf] = joy raqami. O'rtacha bir bosishga ketadigan vaqt (soniya). */
function cost(pos) {
  let c = 0;
  if (MODE === 'one') {
    for (let i = 0; i < N; i++) {
      const pi = pos[i];
      c += rawUni[i] * reach[pi] + (first[i] + last[i]) * spaceT[pi];
      const row = i * N;
      for (let j = 0; j < N; j++) {
        const f = rawBig[row + j];
        if (f) c += f * pairT[pi * S + pos[j]];
      }
    }
    return c / (total + wordTotal);
  }
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
const tezkor = [
  [TUTUQ, 'x', 'p', 'f', 'e', 'z', 'm', 'q', 'v', 'h'],
  ['j', 'o', 'u', 'ç', 'y', 'd', 'l', 's', 'k'],
  ['a', 'g', 'i', 'b', 'n', 'r', 't'],
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
    const b = Math.floor(rand() * N);
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
const meanDist = (pos) => {
  // ketma-ket harflar orasidagi o'rtacha masofa (tugma kengligida)
  let s = 0;
  for (let i = 0; i < N; i++) {
    for (let j = 0; j < N; j++) {
      const a = slots[pos[i]];
      const b = slots[pos[j]];
      s += big[i * N + j] * Math.hypot(a.x - b.x, a.y - b.y);
    }
  }
  return s;
};
const report = (name, rows) => {
  const pos = fromRows(rows);
  const extra = MODE === 'one'
    ? `harflar orasidagi o'rtacha masofa ${meanDist(pos).toFixed(2)} tugma`
    : `barmoq navbatlashuvi ${(alt(pos) * 100).toFixed(0)}%`;
  console.log(`\n${name.padEnd(14)} ${fmt(rows)}`);
  console.log(`  o'rtacha ${(cost(pos) * 1000).toFixed(1)} ms/bosish, ${extra}`);
};
console.log(`\nRejim: ${MODE === 'one' ? "bir qo'l (o'ng bosh barmoq)" : 'ikki bosh barmoq'}`);
report('QWERTY (Söz):', qwerty);
report('Tezkor:', tezkor);
report('Eng yaxshisi:', toRows(best));
console.log(`\nModel bo'yicha QWERTY'dan farq: ${(((base - bestCost) / base) * 100).toFixed(1)}% tezroq`);
console.log(`JSON: ${JSON.stringify(toRows(best))}`);
