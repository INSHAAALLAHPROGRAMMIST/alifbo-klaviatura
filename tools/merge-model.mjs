#!/usr/bin/env node
// Taqsimlangan qurish, 2-bosqich: count-shard.mjs bo'laklarini birlashtirib ALM2 model yozadi.
// Fayllar kalit bo'yicha saralangani uchun hammasi oqim bilan (k yo'lli birlashtirish) o'qiladi:
// xotiraga faqat lug'at va saqlanadigan kontekstlar sig'ishi kerak.
//
//   node tools/merge-model.mjs --lang uz --in counts/*/uz --out lm_uz.bin
//   node tools/merge-model.mjs --lang uz --in counts/*/uz extra/uz@16 --out lm_uz.bin   # extra x16 og'irlik
//
// --max-vocab 65000 --min-count 3 --min-bigram 2 --ctx-min 5 --k-high 4 --max-order 5
// --keep3 200000 --keep4 100000 --keep5 50000
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import zlib from 'node:zlib';
import { K, LANGS, START, encodeAlm2, q } from './lm-common.mjs';

const args = process.argv.slice(2);
function opt(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : def;
}
const lang = opt('lang');
const out = opt('out');
const maxVocab = Math.min(65000, Number(opt('max-vocab', '65000')));
const minUni = Number(opt('min-count', '3'));
const minBi = Number(opt('min-bigram', '2'));
const ctxMin = Number(opt('ctx-min', '5'));
const kHigh = Number(opt('k-high', '4'));
const maxOrder = Math.min(5, Math.max(2, Number(opt('max-order', '5'))));
const keepCap = { 3: Number(opt('keep3', '200000')), 4: Number(opt('keep4', '100000')), 5: Number(opt('keep5', '50000')) };
// --norm N: yozishdan oldin sanoqlar korpus N so'zli bo'lgandek kichraytiriladi. Ilovadagi ball qoidalari
// (shaxsiy so'zlar ustunligi, avtotuzatish chegarasi) ~1e8 so'zli model shkalasiga sozlangan, q esa
// 255 da kesiladi: milliardlab so'zda eng ko'p uchraydiganlar bir-biridan ajralmay qoladi.
const norm = Number(opt('norm', '0'));
// --confusion-ratio R: o'zbekcha harflarsiz yozilgan xato shakllarni lug'atdan chiqaradi: kirill klaviaturasidan
// (булган, куп, хозир, булған) va lotinda apostrofsiz (bolgan, kop, togri). 1-2 harfni almashtirganda R marta
// ko'p uchraydigan so'z chiqsa, so'z xato deb olinadi. Lug'atda qolsa, ilova uni "ma'lum so'z" deb tuzatmaydi
// yoki "bulgan" ni "bölgan" emas, "bulğan" qiladi. keep — shu yo'l bilan tushib qoladigan haqiqiy so'zlar.
const confusionRatio = Number(opt('confusion-ratio', '0'));
const CONFUSION = {
  uz: {
    map: { u: 'ö', k: 'q', x: 'h', g: 'ğ', ğ: 'g', o: 'ö', s: 'ş', c: 'ç' },
    keep: ['tök', 'urin', 'olis', 'sahar', 'tort', 'bos', 'kupol', 'osdi', 'qis', 'isi'],
  },
  uzc: { map: { у: 'ў', к: 'қ', х: 'ҳ', г: 'ғ', ғ: 'г' }, keep: ['тўк', 'урин', 'купол'] },
};
// "papka@N" — shu bo'lak sanoqlari N ga ko'paytiriladi (kichik, lekin muhim korpusga og'irlik berish uchun)
const dirs = [];
{
  const i = args.indexOf('--in');
  if (i >= 0) {
    for (let j = i + 1; j < args.length && !args[j].startsWith('--'); j++) {
      const m = /^(.*)@(\d+(?:\.\d+)?)$/.exec(args[j]);
      dirs.push(m ? { dir: m[1], mult: Number(m[2]) } : { dir: args[j], mult: 1 });
    }
  }
}
if (!LANGS.includes(lang) || !out || dirs.length === 0) {
  console.error('Foydalanish: node tools/merge-model.mjs --lang uz|uzc|en|ru --in papka... --out chiqish.bin');
  process.exit(2);
}

const t0 = Date.now();
const log = (msg) => console.error(`${lang}: ${msg} (${Math.round((Date.now() - t0) / 1000)} s)`);

/** Bitta saralangan gzip fayldan [kalit, son * mult]. */
async function* readGz(file, mult) {
  const rl = readline.createInterface({ input: fs.createReadStream(file).pipe(zlib.createGunzip()), crlfDelay: Infinity });
  for await (const line of rl) {
    const i = line.lastIndexOf('\t');
    if (i > 0) yield [line.slice(0, i), Number(line.slice(i + 1)) * mult];
  }
}

/** Barcha bo'laklardagi bir xil kalitlarni qo'shib, kalit tartibida beradi. */
async function* mergeSum(name) {
  const files = dirs.map((d) => ({ f: path.join(d.dir, name), mult: d.mult })).filter((x) => fs.existsSync(x.f));
  const its = files.map((x) => readGz(x.f, x.mult)[Symbol.asyncIterator]());
  // kichik uyum (heap): [kalit, son, manba]
  const heap = [];
  const less = (x, y) => x[0] < y[0];
  const push = (e) => {
    heap.push(e);
    let i = heap.length - 1;
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (!less(heap[i], heap[p])) break;
      [heap[i], heap[p]] = [heap[p], heap[i]];
      i = p;
    }
  };
  const pop = () => {
    const top = heap[0];
    const last = heap.pop();
    if (heap.length > 0) {
      heap[0] = last;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1;
        const r = l + 1;
        let s = i;
        if (l < heap.length && less(heap[l], heap[s])) s = l;
        if (r < heap.length && less(heap[r], heap[s])) s = r;
        if (s === i) break;
        [heap[i], heap[s]] = [heap[s], heap[i]];
        i = s;
      }
    }
    return top;
  };
  for (let i = 0; i < its.length; i++) {
    const r = await its[i].next();
    if (!r.done) push([r.value[0], r.value[1], i]);
  }
  let key = null;
  let sum = 0;
  while (heap.length > 0) {
    const [k, c, i] = pop();
    if (k === key) {
      sum += c;
    } else {
      if (key !== null) yield [key, sum];
      key = k;
      sum = c;
    }
    const r = await its[i].next();
    if (!r.done) push([r.value[0], r.value[1], i]);
  }
  if (key !== null) yield [key, sum];
}

// ---------------------------------------------------------------- lug'at
let tokenTotal = 0;
let distinct = 0;
let kept = [];
for await (const [w, c] of mergeSum('uni.tsv.gz')) {
  tokenTotal += c;
  distinct++;
  if (c >= minUni) kept.push([w, c]);
}
kept.sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1));
if (confusionRatio > 0 && CONFUSION[lang]) {
  const { map, keep } = CONFUSION[lang];
  const all = new Map(kept);
  const keepSet = new Set(keep);
  /** 1-2 harfi almashtirilgan shakllar. */
  const variants = (w) => {
    const pos = [];
    for (let i = 0; i < w.length; i++) if (map[w[i]]) pos.push(i);
    const res = [];
    const sub = (s, i) => s.slice(0, i) + map[w[i]] + s.slice(i + 1);
    for (let a = 0; a < pos.length; a++) {
      const one = sub(w, pos[a]);
      res.push(one);
      for (let b = a + 1; b < pos.length; b++) res.push(sub(one, pos[b]));
    }
    return res;
  };
  const dropped = [];
  // faqat lug'atga kirishi mumkin bo'lganlar tekshiriladi (ro'yxat boshidan, tushganlar o'rniga keyingilar keladi)
  const out = [];
  for (const e of kept) {
    if (out.length >= maxVocab - 1) break;
    const [w, c] = e;
    if (w.length >= 3 && !keepSet.has(w)) {
      let better = null;
      for (const v of variants(w)) {
        const cv = all.get(v);
        if (cv !== undefined && cv >= confusionRatio * c && (!better || cv > better[1])) better = [v, cv];
      }
      if (better) {
        dropped.push(`${w}>${better[0]}`);
        continue;
      }
    }
    out.push(e);
  }
  kept = out;
  log(`xato shakllar (x${confusionRatio}): ${dropped.length} ta chiqarildi: ${dropped.slice(0, 30).join(' ')}`);
}
kept = kept.slice(0, maxVocab - 1);
const countOf = new Map(kept);
const vocab = [START, ...kept.map(([w]) => w)].sort();
if (vocab[0] !== START) throw new Error('"<s>" 0-o\'rinda bo\'lishi kerak');
const idOf = new Map(vocab.map((w, i) => [w, i]));
kept = null;
log(`${dirs.length} bo'lak, ${tokenTotal} so'z (vazn bilan), ${distinct} xil, lug'at ${vocab.length}`);

const cmpEntries = (a, b) => b[1] - a[1] || (vocab[a[0]] < vocab[b[0]] ? -1 : 1);

/** Kalit bo'yicha saralangan birikmalarni kontekst (oxirgi so'zdan oldingisi) bo'yicha guruhlab beradi. */
async function* groups(name) {
  let ctx = null;
  let items = [];
  for await (const [k, c] of mergeSum(name)) {
    const sp = k.lastIndexOf(' ');
    const head = k.slice(0, sp);
    if (head !== ctx) {
      if (ctx !== null) yield [ctx, items];
      ctx = head;
      items = [];
    }
    items.push([k.slice(sp + 1), c]);
  }
  if (ctx !== null) yield [ctx, items];
}

/** Konteksdagi davomlar: lug'atdagilar, kamida minBi marta, eng ko'p uchraganlari oldinda. */
function topEntries(items, limit) {
  let total = 0;
  const entries = [];
  for (const [w, c] of items) {
    total += c;
    const id = idOf.get(w);
    if (id !== undefined && c >= minBi) entries.push([id, c]);
  }
  entries.sort(cmpEntries);
  if (entries.length > limit) entries.length = limit;
  return { total, entries };
}

// ---------------------------------------------------------------- 1 so'zli kontekst (bigram)
const bigramFinal = Array.from({ length: vocab.length }, () => []);
{
  let rowsWith = 0;
  for await (const [ctx, items] of groups('ng2.tsv.gz')) {
    const a = idOf.get(ctx);
    if (a === undefined) continue;
    const { entries } = topEntries(items, K);
    bigramFinal[a] = entries;
    if (entries.length) rowsWith++;
  }
  log(`1 so'zli kontekst: ${rowsWith} so'zda davom bor`);
}

// ---------------------------------------------------------------- yuqori tartiblar (3..maxOrder)
const finalByOrder = {};
const storedByOrder = {};

function lowerTop(ctx) {
  // ctx'ning bir so'z qisqa qo'shimchasi uchun eng yaxshi davomlar (build-model.mjs bilan bir xil)
  const suffix = ctx.slice(1);
  for (let m = ctx.length; m >= 3; m--) {
    const s = suffix.slice(suffix.length - (m - 1));
    const e = finalByOrder[m]?.get(s.join(','));
    if (e) return e;
  }
  return bigramFinal[suffix[suffix.length - 1]];
}

for (let n = 3; n <= maxOrder; n++) {
  let stored = [];
  let candidates = 0;
  let redundant = 0;
  const cap = keepCap[n];
  for await (const [ctxStr, items] of groups(`ng${n}.tsv.gz`)) {
    const ws = ctxStr.split(' ');
    if (ws.length !== n - 1) continue;
    const ctx = new Array(n - 1);
    let ok = true;
    for (let i = 0; i < ws.length; i++) {
      const id = idOf.get(ws[i]);
      if (id === undefined) {
        ok = false;
        break;
      }
      ctx[i] = id;
    }
    if (!ok) continue;
    const { total, entries } = topEntries(items, kHigh);
    if (total < ctxMin || entries.length === 0) continue;
    candidates++;
    const lower = lowerTop(ctx);
    const cmpLen = Math.min(3, entries.length);
    if (lower && lower.length >= cmpLen && entries.slice(0, cmpLen).every((e, j) => lower[j][0] === e[0])) {
      redundant++;
      continue;
    }
    stored.push({ ctx, entries, total });
    if (stored.length > cap * 4) {
      stored.sort((x, y) => y.total - x.total);
      stored.length = cap;
    }
  }
  stored.sort((x, y) => y.total - x.total);
  const useful = candidates - redundant;
  const keep = stored.slice(0, cap);
  stored = null;
  keep.sort((x, y) => {
    for (let j = 0; j < x.ctx.length; j++) if (x.ctx[j] !== y.ctx[j]) return x.ctx[j] - y.ctx[j];
    return 0;
  });
  finalByOrder[n] = new Map(keep.map((e) => [e.ctx.join(','), e.entries]));
  storedByOrder[n] = keep;
  log(`${n} so'zli: ${candidates} nomzod, ${useful} foydali (${redundant} ortiqcha), ${keep.length} saqlandi`);
}

// ---------------------------------------------------------------- yozish
// Tanlash va saralash xom sanoq bilan bo'ldi; normallash faqat q (log2 * 10) shkalasini suradi.
const f = norm > 0 ? norm / tokenTotal : 1;
const scaleRow = (row) => row.map(([id, c]) => [id, c * f]);
if (f !== 1) {
  for (let i = 0; i < bigramFinal.length; i++) bigramFinal[i] = scaleRow(bigramFinal[i]);
  for (const n in storedByOrder) for (const e of storedByOrder[n]) e.entries = scaleRow(e.entries);
  log(`normallash: x${f.toExponential(3)} (${norm} so'zli korpus shkalasi)`);
}
const { data, bigramEntries, higherInfo } = encodeAlm2(
  vocab,
  (i) => (vocab[i] === START ? 0 : q(countOf.get(vocab[i]) * f)),
  bigramFinal,
  storedByOrder,
  maxOrder,
);
fs.writeFileSync(out, data);
console.log(
  `${lang}: ${dirs.length} bo'lak, ${tokenTotal} so'z, lug'at ${vocab.length}, ${bigramEntries} juftlik` +
    (higherInfo.length ? `, kontekstlar ${higherInfo.join(' ')}` : '') +
    `, ${data.length} bayt -> ${out}`,
);
