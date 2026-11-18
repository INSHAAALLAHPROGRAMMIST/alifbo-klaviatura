#!/usr/bin/env node
// Taqsimlangan qurish, 1-bosqich: katta korpusning bir bo'lagini sanaydi (har bo'lak alohida serverda).
// Natijani merge-model.mjs birlashtirib ALM2 model yozadi (.github/workflows/corpus.yml).
//
//   node tools/count-shard.mjs --lang uz --in uz.txt --out counts/uz
//
// Kirish qatorlari: "vazn<TAB>matn" (vazn — butun son, manbaga qarab; tools/corpus-to-text.py yozadi).
// Chiqish (--out papkasida, kalit bo'yicha saralangan, gzip):
//   uni.tsv.gz            so'z<TAB>son                    (son >= --min, aniq sanoq)
//   ng2.tsv.gz..ng5.tsv.gz "s1 s2 .. sn"<TAB>son         (son >= --min, har tartibda eng ko'pi --top2 / --top ta)
//
// Xotira chegarali: n-so'zli birikmalar "lossy" sanaladi — jadval --cap dan oshsa kam uchraganlari tashlanadi
// (tez-tez uchraydiganlar baribir saqlanadi, modelga faqat shular kiradi). Birikmalar faqat bo'lakdagi eng
// ko'p uchraydigan 65 534 so'zdan tuziladi, qolgan so'zlar kontekstni uzadi.
//
// --min 2  --top2 6000000  --top 3000000  --cap2 32000000 --cap3 24000000 --cap4 16000000 --cap5 16000000
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import zlib from 'node:zlib';
import { once } from 'node:events';
import { LANGS, START, makeTokenizer } from './lm-common.mjs';

const args = process.argv.slice(2);
function opt(name, def) {
  const i = args.indexOf('--' + name);
  return i >= 0 ? args[i + 1] : def;
}
const lang = opt('lang');
const outDir = opt('out');
const minOut = Number(opt('min', '2'));
const topOut = { 2: Number(opt('top2', '6000000')), 3: Number(opt('top', '3000000')) };
topOut[4] = topOut[3];
topOut[5] = topOut[3];
const caps = {
  2: Number(opt('cap2', '32000000')),
  3: Number(opt('cap3', '24000000')),
  4: Number(opt('cap4', '16000000')),
  5: Number(opt('cap5', '16000000')),
};
const inputs = [];
{
  const i = args.indexOf('--in');
  if (i >= 0) for (let j = i + 1; j < args.length && !args[j].startsWith('--'); j++) inputs.push(args[j]);
}
if (!LANGS.includes(lang) || !outDir || inputs.length === 0) {
  console.error('Foydalanish: node tools/count-shard.mjs --lang uz|uzc|en|ru --in fayl... --out papka');
  process.exit(2);
}
fs.mkdirSync(outDir, { recursive: true });

const { segments } = makeTokenizer(lang);
const t0 = Date.now();
const log = (msg) => console.error(`${lang}: ${msg} (${Math.round((Date.now() - t0) / 1000)} s)`);

/** V8'da bitta Map ~16.7 mln yozuvdan oshmaydi: kalitning birinchi belgisi bo'yicha bo'lingan Map. */
class BigMap {
  constructor(parts = 16) {
    this.parts = Array.from({ length: parts }, () => new Map());
    this.mask = parts - 1;
  }
  part(key) {
    return this.parts[key.charCodeAt(0) & this.mask];
  }
  get size() {
    let s = 0;
    for (const m of this.parts) s += m.size;
    return s;
  }
  *entries() {
    for (const m of this.parts) yield* m;
  }
  clear() {
    for (const m of this.parts) m.clear();
  }
}

// ---------------------------------------------------------------- 1-o'tish: so'zlar (aniq) + raqamli nusxa
// Raqamli nusxa (u32): 0xFFFFFFFF = gap boshi, 0xFFFFFFFE = raqamdan keyingi bo'lak, 0xFFFFFF00 + v = vazn v.
const SEG_START = 0xffffffff;
const SEG_CONT = 0xfffffffe;
const WEIGHT = 0xffffff00;
const idsFile = path.join(outDir, 'ids.tmp');
const fd = fs.openSync(idsFile, 'w');
const wbuf = new Uint32Array(1 << 20);
let wpos = 0;
function emit(v) {
  wbuf[wpos++] = v;
  if (wpos === wbuf.length) {
    fs.writeSync(fd, Buffer.from(wbuf.buffer, 0, wpos * 4));
    wpos = 0;
  }
}

const idOf = new BigMap();
const words = [];
let counts = new Float64Array(1 << 20);
let lines = 0;
let tokenCount = 0;
let weighted = 0;
let curWeight = -1;
for (const file of inputs) {
  const rl = readline.createInterface({ input: fs.createReadStream(file, { encoding: 'utf8' }), crlfDelay: Infinity });
  for await (const line of rl) {
    lines++;
    const tab = line.indexOf('\t');
    let w = 1;
    let text = line;
    if (tab > 0 && tab < 4) {
      const v = Number(line.slice(0, tab));
      if (Number.isInteger(v) && v >= 1 && v <= 255) {
        w = v;
        text = line.slice(tab + 1);
      }
    }
    if (w !== curWeight) {
      emit(WEIGHT + w);
      curWeight = w;
    }
    for (const { ts, start } of segments(text)) {
      emit(start ? SEG_START : SEG_CONT);
      for (const t of ts) {
        const m = idOf.part(t);
        let id = m.get(t);
        if (id === undefined) {
          id = words.length;
          m.set(t, id);
          words.push(t);
          if (id >= counts.length) {
            const bigger = new Float64Array(counts.length * 2);
            bigger.set(counts);
            counts = bigger;
          }
        }
        counts[id] += w;
        emit(id);
        tokenCount++;
        weighted += w;
      }
    }
    if (lines % 2000000 === 0) log(`${lines} qator, ${tokenCount} so'z, ${words.length} xil`);
  }
}
if (wpos > 0) fs.writeSync(fd, Buffer.from(wbuf.buffer, 0, wpos * 4));
fs.closeSync(fd);
idOf.clear();
log(`1-o'tish: ${lines} qator, ${tokenCount} so'z (vazn bilan ${weighted}), ${words.length} xil`);

/** Saralangan qatorlarni gzip faylga yozadi. */
async function writeGz(file, rows) {
  const gz = zlib.createGzip({ level: 6 });
  const ws = fs.createWriteStream(file);
  gz.pipe(ws);
  let buf = [];
  let size = 0;
  for (const [k, c] of rows) {
    const s = `${k}\t${c}`;
    buf.push(s);
    size += s.length + 1;
    if (size > 1 << 20) {
      if (!gz.write(buf.join('\n') + '\n')) await once(gz, 'drain');
      buf = [];
      size = 0;
    }
  }
  if (buf.length) gz.write(buf.join('\n') + '\n');
  gz.end();
  await once(ws, 'finish');
}

const byKey = (a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0);

// so'zlar: aniq sanoq
{
  const rows = [];
  for (let i = 0; i < words.length; i++) if (counts[i] >= minOut) rows.push([words[i], counts[i]]);
  rows.sort(byKey);
  await writeGz(path.join(outDir, 'uni.tsv.gz'), rows);
  log(`so'zlar: ${rows.length} ta (son >= ${minOut})`);
}

// ---------------------------------------------------------------- 2-o'tish: 2..5 so'zli birikmalar
// Bo'lakdagi eng ko'p uchraydigan 65 534 so'z 1..65534 raqam oladi (0 = gap boshi); kalit — shu raqamlar
// belgilaridan iborat qisqa satr.
const LOCAL = 65534;
const order = Array.from({ length: words.length }, (_, i) => i).sort((a, b) => counts[b] - counts[a]);
const local = new Int32Array(words.length).fill(-1);
const localWords = [START];
for (let r = 0; r < Math.min(LOCAL, order.length); r++) {
  local[order[r]] = r + 1;
  localWords.push(words[order[r]]);
}
order.length = 0;

const maps = { 2: new BigMap(), 3: new BigMap(), 4: new BigMap(), 5: new BigMap() };
const floor = { 2: 0, 3: 0, 4: 0, 5: 0 };
const pruneCap = { 2: caps[2] / 16, 3: caps[3] / 16, 4: caps[4] / 16, 5: caps[5] / 16 };

/** Jadval to'lsa: kam uchraganlarni tashlab, joy ochadi. */
function prune(n, m) {
  let t = Math.max(1, floor[n]);
  for (;;) {
    for (const [k, c] of m) if (c <= t) m.delete(k);
    if (m.size <= pruneCap[n] * 0.6) break;
    t++;
  }
  floor[n] = t;
}

function add(n, key, w) {
  const big = maps[n];
  const m = big.part(key);
  m.set(key, (m.get(key) ?? 0) + w);
  if (m.size > pruneCap[n]) prune(n, m);
}

{
  const rfd = fs.openSync(idsFile, 'r');
  const buf = Buffer.alloc(1 << 24);
  const fc = String.fromCharCode;
  let w = 1;
  // oyna: oxirgi 4 ta mahalliy raqam (a — eng eskisi)
  let len = 0;
  let a = 0;
  let b = 0;
  let c = 0;
  let d = 0;
  let leftover = 0;
  for (;;) {
    const n = fs.readSync(rfd, buf, leftover, buf.length - leftover, null);
    const total = leftover + n;
    if (total < 4) break;
    const usable = total - (total % 4);
    for (let p = 0; p < usable; p += 4) {
      const v = buf.readUInt32LE(p);
      if (v === SEG_START) {
        len = 1;
        d = 0;
      } else if (v === SEG_CONT) {
        len = 0;
      } else if (v >= WEIGHT) {
        w = v - WEIGHT;
      } else {
        const x = local[v];
        if (x < 0) {
          len = 0;
          continue;
        }
        if (len >= 1) add(2, fc(d, x), w);
        if (len >= 2) add(3, fc(c, d, x), w);
        if (len >= 3) add(4, fc(b, c, d, x), w);
        if (len >= 4) add(5, fc(a, b, c, d, x), w);
        a = b;
        b = c;
        c = d;
        d = x;
        if (len < 4) len++;
      }
    }
    leftover = total - usable;
    if (leftover > 0) buf.copy(buf, 0, usable, total);
    if (n <= 0) break;
  }
  fs.closeSync(rfd);
}
fs.rmSync(idsFile, { force: true });
log(`2-o'tish tugadi; tashlangan chegaralar: 2:${floor[2]} 3:${floor[3]} 4:${floor[4]} 5:${floor[5]}`);

for (let n = 2; n <= 5; n++) {
  let rows = [];
  for (const [k, cnt] of maps[n].entries()) if (cnt >= minOut) rows.push([k, cnt]);
  maps[n].clear();
  const all = rows.length;
  if (rows.length > topOut[n]) {
    rows.sort((x, y) => y[1] - x[1]);
    rows.length = topOut[n];
  }
  rows = rows.map(([k, cnt]) => {
    const ws = new Array(k.length);
    for (let i = 0; i < k.length; i++) ws[i] = localWords[k.charCodeAt(i)];
    return [ws.join(' '), cnt];
  });
  rows.sort(byKey);
  await writeGz(path.join(outDir, `ng${n}.tsv.gz`), rows);
  log(`${n} so'zli: ${all} ta (son >= ${minOut}), ${rows.length} yozildi`);
}
console.log(`${lang}: ${lines} qator, ${tokenCount} so'z, ${words.length} xil so'z -> ${outDir}`);
