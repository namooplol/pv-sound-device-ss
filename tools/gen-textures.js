// Generates the 16x16 pixel-art textures for the mod. Run: node tools/gen-textures.js
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'pvsound', 'textures');

// ---------------------------------------------------------------- png encoder
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function png(img) {
  const { w, h, px } = img;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const c = px[y * w + x];
      const o = y * (w * 4 + 1) + 1 + x * 4;
      raw[o] = (c >>> 24) & 0xff;
      raw[o + 1] = (c >>> 16) & 0xff;
      raw[o + 2] = (c >>> 8) & 0xff;
      raw[o + 3] = c & 0xff;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- drawing helpers
function hex(s, a = 255) {
  const v = parseInt(s.replace('#', ''), 16);
  return ((v << 8) | a) >>> 0;
}
const CLEAR = 0;

function canvas(fill = CLEAR) {
  return { w: 16, h: 16, px: new Array(256).fill(fill) };
}
function set(img, x, y, c) {
  if (x >= 0 && y >= 0 && x < img.w && y < img.h) img.px[y * img.w + x] = c;
}
function rect(img, x0, y0, x1, y1, c) {
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) set(img, x, y, c);
}
function shade(c, f) {
  const r = Math.min(255, Math.round(((c >>> 24) & 0xff) * f));
  const g = Math.min(255, Math.round(((c >>> 16) & 0xff) * f));
  const b = Math.min(255, Math.round(((c >>> 8) & 0xff) * f));
  return ((r << 24) | (g << 16) | (b << 8) | (c & 0xff)) >>> 0;
}
// deterministic noise so re-runs give identical files
let seed = 1337;
function rnd() {
  seed = (seed * 1103515245 + 12345) & 0x7fffffff;
  return seed / 0x7fffffff;
}
function circle(img, cx, cy, r, c, filled = true) {
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
    if (filled ? d <= r : Math.abs(d - r) < 0.5) set(img, x, y, c);
  }
}
function save(dir, name, img) {
  const file = path.join(ROOT, dir, name + '.png');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, png(img));
  console.log('wrote', path.relative(ROOT, file));
}

// ---------------------------------------------------------------- palettes
const WALNUT = hex('#6b4428');
const WALNUT_DARK = hex('#4a2d19');
const WALNUT_LIGHT = hex('#80542f');
const GRILLE = hex('#17171b');
const GRILLE_HI = hex('#26262c');
const CONE = hex('#303036');
const CONE_RING = hex('#4a4a52');
const CAP = hex('#5c5c66');
const METAL = hex('#8a919c');
const METAL_DARK = hex('#5d636d');
const PANEL = hex('#1f2430');
const PANEL_HI = hex('#2b3242');

function woodGrain(base, dark, light) {
  const img = canvas(base);
  for (let y = 0; y < 16; y++) {
    const wave = Math.round(Math.sin(y * 0.9) * 1.5);
    for (let x = 0; x < 16; x++) {
      const r = rnd();
      if ((x + wave) % 5 === 0) set(img, x, y, dark);
      else if (r > 0.86) set(img, x, y, light);
      else if (r < 0.08) set(img, x, y, shade(base, 0.9));
    }
  }
  return img;
}

// ---------------------------------------------------------------- speaker
function speakerFront(led) {
  const img = woodGrain(WALNUT, WALNUT_DARK, WALNUT_LIGHT);
  // grille inset (element spans x 2..13)
  rect(img, 3, 1, 12, 14, GRILLE);
  for (let y = 1; y <= 14; y++) for (let x = 3; x <= 12; x++) if ((x + y) % 2 === 0) set(img, x, y, GRILLE_HI);
  // tweeter
  circle(img, 8, 4, 1.8, CONE_RING);
  circle(img, 8, 4, 0.9, CAP);
  // woofer
  circle(img, 8, 10.5, 3.9, CONE_RING);
  circle(img, 8, 10.5, 3.2, CONE);
  circle(img, 8, 10.5, 2.2, shade(CONE, 1.15), false);
  circle(img, 8, 10.5, 1.2, CAP);
  set(img, 7, 9, shade(CAP, 1.4));
  // channel LED + its glow
  set(img, 11, 2, led);
  set(img, 11, 1, shade(led, 0.55));
  // frame edges
  rect(img, 2, 0, 13, 0, WALNUT_DARK);
  rect(img, 2, 15, 13, 15, WALNUT_DARK);
  return img;
}

save('block', 'speaker_front_left', speakerFront(hex('#3ba7ff')));
save('block', 'speaker_front_right', speakerFront(hex('#ff4a4a')));
save('block', 'speaker_front_mono', speakerFront(hex('#5bff6a')));
save('block', 'speaker_side', woodGrain(WALNUT, WALNUT_DARK, WALNUT_LIGHT));
(() => {
  const img = woodGrain(WALNUT_LIGHT, WALNUT, hex('#946339'));
  save('block', 'speaker_top', img);
})();
(() => {
  // back panel with binding posts
  const img = woodGrain(WALNUT, WALNUT_DARK, WALNUT_LIGHT);
  rect(img, 5, 10, 10, 13, hex('#2a2a2e'));
  set(img, 6, 11, hex('#d23b3b'));
  set(img, 9, 11, hex('#202020'));
  set(img, 6, 12, hex('#c79a3a'));
  set(img, 9, 12, hex('#c79a3a'));
  circle(img, 8, 5, 1.5, hex('#121214'));
  save('block', 'speaker_back', img);
})();

// ---------------------------------------------------------------- subwoofer
(() => {
  const vinyl = hex('#202024');
  const img = canvas(vinyl);
  for (let i = 0; i < 40; i++) set(img, Math.floor(rnd() * 16), Math.floor(rnd() * 16), hex('#2a2a30'));
  rect(img, 0, 0, 15, 0, hex('#121215'));
  rect(img, 0, 15, 15, 15, hex('#121215'));
  rect(img, 0, 0, 0, 15, hex('#121215'));
  rect(img, 15, 0, 15, 15, hex('#121215'));
  circle(img, 8, 8, 6.9, METAL_DARK);
  circle(img, 8, 8, 6.2, hex('#3a3a42'));
  circle(img, 8, 8, 5.4, CONE);
  circle(img, 8, 8, 4.2, shade(CONE, 1.15), false);
  circle(img, 8, 8, 3.0, shade(CONE, 0.85), false);
  circle(img, 8, 8, 2.0, CAP);
  set(img, 7, 7, shade(CAP, 1.4));
  for (const [x, y] of [[2, 2], [13, 2], [2, 13], [13, 13]]) set(img, x, y, METAL);
  set(img, 13, 14, hex('#ffb52e'));
  save('block', 'subwoofer_front', img);

  const side = canvas(vinyl);
  for (let i = 0; i < 40; i++) set(side, Math.floor(rnd() * 16), Math.floor(rnd() * 16), hex('#2a2a30'));
  rect(side, 0, 0, 15, 0, hex('#121215'));
  rect(side, 0, 15, 15, 15, hex('#121215'));
  save('block', 'subwoofer_side', side);
  // bass port on top
  const top = canvas(vinyl);
  for (let i = 0; i < 40; i++) set(top, Math.floor(rnd() * 16), Math.floor(rnd() * 16), hex('#2a2a30'));
  circle(top, 8, 8, 2.6, METAL_DARK);
  circle(top, 8, 8, 1.9, hex('#08080a'));
  save('block', 'subwoofer_top', top);
})();

// ---------------------------------------------------------------- mixer
function mixerTop(lit) {
  const img = canvas(PANEL);
  rect(img, 0, 0, 15, 0, METAL);
  rect(img, 0, 15, 15, 15, METAL);
  rect(img, 0, 0, 0, 15, METAL);
  rect(img, 15, 0, 15, 15, METAL);
  const knobColors = ['#3ba7ff', '#ff4a4a', '#5bff6a', '#ffb52e'];
  const faderPos = [9, 11, 8, 12];
  for (let i = 0; i < 4; i++) {
    const x = 2 + i * 3;
    // knobs
    set(img, x, 2, hex(knobColors[i]));
    set(img, x + 1, 2, shade(hex(knobColors[i]), 0.6));
    set(img, x, 4, hex('#c8ccd4'));
    // fader slot + cap
    rect(img, x, 6, x, 13, hex('#0b0d12'));
    rect(img, x - 0 , faderPos[i], x + 1, faderPos[i], hex('#e9ecf2'));
  }
  // crossfader
  rect(img, 3, 14, 12, 14, hex('#0b0d12'));
  rect(img, lit ? 9 : 6, 14, (lit ? 9 : 6) + 1, 14, hex('#e9ecf2'));
  // vu meter column at right edge
  for (let y = 2; y <= 12; y++) {
    const on = lit && y >= 5;
    const c = y <= 4 ? '#ff3030' : y <= 6 ? '#ffd030' : '#5bff6a';
    set(img, 14, y, on ? hex(c) : shade(hex(c), 0.25));
  }
  set(img, 13, 1, lit ? hex('#ff3030') : hex('#401010')); // "on air"
  return img;
}
save('block', 'mixer_top', mixerTop(false));
save('block', 'mixer_top_on', mixerTop(true));

function mixerFront(lit) {
  const img = canvas(METAL);
  // the 7px tall console only shows rows 9..15
  rect(img, 0, 9, 15, 9, shade(METAL, 1.2));
  rect(img, 0, 15, 15, 15, METAL_DARK);
  for (let i = 0; i < 4; i++) {
    const x = 2 + i * 3;
    circle(img, x + 0.5, 12.5, 1.1, hex('#1a1a1e'));
    set(img, x, 12, hex('#444'));
  }
  set(img, 14, 11, lit ? hex('#5bff6a') : hex('#16401a'));
  set(img, 14, 13, lit ? hex('#ffb52e') : hex('#40300f'));
  return img;
}
save('block', 'mixer_front', mixerFront(false));
save('block', 'mixer_front_on', mixerFront(true));
(() => {
  const img = canvas(METAL);
  rect(img, 0, 9, 15, 9, shade(METAL, 1.2));
  rect(img, 0, 15, 15, 15, METAL_DARK);
  for (let x = 1; x < 15; x += 2) set(img, x, 12, METAL_DARK);
  save('block', 'mixer_side', img);
})();

// ---------------------------------------------------------------- items
(() => {
  const img = canvas();
  const cable = hex('#1c1c20');
  const hi = hex('#3a3a42');
  // coil
  circle(img, 7, 8, 5, cable, false);
  circle(img, 7, 8, 3.6, cable, false);
  circle(img, 7, 8, 5.5, hi, false);
  // tail with copper jack
  rect(img, 11, 11, 12, 11, cable);
  set(img, 13, 12, cable);
  set(img, 14, 13, hex('#c87533'));
  set(img, 15, 14, hex('#e8a060'));
  set(img, 13, 13, hex('#2d6cdf'));
  save('item', 'speaker_cable', img);
})();

function microphone(on) {
  const img = canvas();
  // grille ball
  circle(img, 10, 5, 3.4, hex('#9aa0aa'));
  circle(img, 10, 5, 3.4, hex('#6d737e'), false);
  for (const [x, y] of [[9, 4], [11, 4], [10, 6], [8, 6], [12, 6], [10, 3]]) set(img, x, y, hex('#c9ced6'));
  // ring
  set(img, 8, 8, hex('#2b2b30'));
  set(img, 7, 8, hex('#2b2b30'));
  set(img, 8, 7, hex('#2b2b30'));
  // handle (diagonal)
  for (let i = 0; i < 6; i++) {
    set(img, 6 - i, 9 + i, hex('#1d1d22'));
    set(img, 7 - i, 9 + i, hex('#34343c'));
  }
  // switch / LED
  set(img, 5, 11, on ? hex('#ff3030') : hex('#4a1515'));
  if (on) set(img, 4, 10, hex('#ff8080', 160));
  return img;
}
save('item', 'microphone', microphone(false));
save('item', 'microphone_on', microphone(true));

// ---------------------------------------------------------------- cable management
(() => {
  // block texture for the clip model (iron hook with a rubber pad)
  const img = canvas(hex('#9aa0aa'));
  for (let i = 0; i < 30; i++) set(img, Math.floor(rnd() * 16), Math.floor(rnd() * 16), hex('#b4bac4'));
  rect(img, 0, 0, 15, 1, hex('#6d737e'));
  rect(img, 0, 14, 15, 15, hex('#2a2a30'));
  save('block', 'cable_clip', img);

  const item = canvas();
  // wall plate
  rect(item, 3, 11, 12, 13, hex('#6d737e'));
  rect(item, 3, 11, 12, 11, hex('#b4bac4'));
  set(item, 4, 12, hex('#3a3a42'));
  set(item, 11, 12, hex('#3a3a42'));
  // hook loop
  rect(item, 5, 5, 5, 10, hex('#9aa0aa'));
  rect(item, 10, 5, 10, 10, hex('#9aa0aa'));
  rect(item, 6, 4, 9, 4, hex('#c9ced6'));
  // cable held in the hook
  rect(item, 0, 8, 15, 9, hex('#1c1c20'));
  rect(item, 0, 8, 15, 8, hex('#3a3a42'));
  rect(item, 5, 8, 5, 9, hex('#9aa0aa'));
  rect(item, 10, 8, 10, 9, hex('#9aa0aa'));
  save('item', 'cable_clip', item);
})();
(() => {
  const img = canvas();
  const cable = hex('#1c1c20');
  const hi = hex('#3a3a42');
  // short loop with a plug on both ends
  circle(img, 8, 8, 4.5, cable, false);
  circle(img, 8, 8, 5.0, hi, false);
  rect(img, 1, 2, 3, 3, hex('#c87533'));
  set(img, 0, 1, hex('#e8a060'));
  set(img, 4, 4, cable);
  rect(img, 12, 12, 14, 13, hex('#c87533'));
  set(img, 15, 14, hex('#e8a060'));
  set(img, 11, 11, cable);
  // "+" badge
  rect(img, 6, 7, 10, 9, hex('#5bff6a'));
  rect(img, 7, 6, 9, 10, hex('#5bff6a'));
  rect(img, 7, 8, 9, 8, hex('#1d6b25'));
  rect(img, 8, 7, 8, 9, hex('#1d6b25'));
  save('item', 'cable_extension', img);
})();
