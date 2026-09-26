// Lightweight protocol-level load generator for SpawnBench. TEST SERVERS ONLY.
//
// Joins bots named <prefix>001, <prefix>002, ... to an offline-mode test server, keeps them alive
// (keepalive, chunk batch acknowledgements, teleport confirmations) and sends a position every second.
// There is no world decoding, pathfinding or rendering: this measures server-side cost, not a human client.
//
// The bot count follows control.json next to this script: {"count": 60}. Lowering the count disconnects the
// highest-numbered bots; {"stop": true} ends the run. Stats are printed as JSON lines every 5 seconds.
//
// usage: node clients.js <host> <port> <version> <prefix> [shard] [shards]
'use strict';
const mc = require('minecraft-protocol');
const fs = require('fs');
const path = require('path');
const { monitorEventLoopDelay } = require('perf_hooks');

const [host, portText, version, prefix = 'bench', shardText = '0', shardsText = '1'] = process.argv.slice(2);
if (!host || !portText || !version) {
  console.error('usage: node clients.js <host> <port> <version> <prefix> [shard] [shards]');
  process.exit(2);
}
const port = Number(portText), shard = Number(shardText), shards = Number(shardsText);
const controlFile = path.join(__dirname, 'control.json');
const JOIN_SPACING_MS = 5000; // one login per 5 s per shard keeps the join path realistic
const sleep = ms => new Promise(r => setTimeout(r, ms));
const emit = o => console.log(JSON.stringify({ at: Date.now(), shard, ...o }));

// Skip decoding of the heaviest clientbound packets; the bench only needs the connection alive.
const opaque = Object.fromEntries(['map_chunk', 'update_light', 'entity_move_look', 'rel_entity_move', 'entity_look',
  'entity_head_rotation', 'entity_metadata', 'entity_velocity', 'entity_teleport', 'multi_block_change', 'block_change',
  'entity_update_attributes', 'entity_equipment', 'block_entity_data'].map(n => ['packet_' + n, 'restBuffer']));

let control = { count: 0 }, finishing = false, errors = 0;
const bots = [];

function connect(index) {
  return new Promise((resolve, reject) => {
    const name = prefix + String(index).padStart(3, '0');
    const bot = { index, name, bytes: 0, chunks: 0, ready: false, ended: false };
    bots.push(bot);
    const timer = setTimeout(() => reject(new Error('join timeout ' + name)), 60000);
    const c = mc.createClient({ host, port, username: name, version, auth: 'offline',
      customPackets: { [version]: { play: { toClient: { types: opaque } } } } });
    bot.client = c;
    c.on('packet', (p, meta, buffer) => { bot.bytes += buffer?.length || 0; });
    c.on('map_chunk', () => bot.chunks++);
    c.on('chunk_batch_finished', () => c.write('chunk_batch_received', { chunksPerTick: 20 }));
    c.on('ping', p => c.write('pong', { id: p.id }));
    c.on('login', () => c.write('settings', { locale: 'en_us', viewDistance: 10, chatFlags: 0, chatColors: true,
      skinParts: 127, mainHand: 1, enableTextFiltering: false, enableServerListing: true, particleStatus: 0 }));
    c.on('position', p => {
      const old = bot.pos || { x: 0, y: 0, z: 0 }, f = p.flags || {};
      bot.pos = { x: f.x ? old.x + p.x : p.x, y: f.y ? old.y + p.y : p.y, z: f.z ? old.z + p.z : p.z };
      c.write('teleport_confirm', { teleportId: p.teleportId });
      c.write('position_look', { ...bot.pos, yaw: p.yaw, pitch: p.pitch, flags: { onGround: false, hasHorizontalCollision: false } });
      c.write('player_loaded', {});
      if (!bot.ready) { bot.ready = true; clearTimeout(timer); emit({ event: 'joined', name }); resolve(bot); }
    });
    c.on('kick_disconnect', p => emit({ event: 'kicked', name, reason: JSON.stringify(p).slice(0, 200) }));
    c.on('error', e => { if (finishing) return; errors++; emit({ event: 'error', name, message: e.message }); if (!bot.ready) reject(e); });
    c.on('end', reason => {
      if (finishing || bot.ended) return;
      bot.ended = true; errors++;
      emit({ event: 'end', name, reason: String(reason) });
      if (!bot.ready) reject(new Error('ended ' + name));
    });
  });
}

// Every tick: acknowledge the tick; once a second: re-send the current position so the server keeps moving state fresh.
let tick = 0;
setInterval(() => {
  tick++;
  for (const b of bots) {
    if (b.ended || b.client?.state !== 'play' || !b.pos) continue;
    try {
      if (tick % 20 === 0) b.client.write('position', { ...b.pos, flags: { onGround: true, hasHorizontalCollision: false } });
      b.client.write('tick_end', {});
    } catch (e) { emit({ event: 'writeError', name: b.name, message: e.message }); }
  }
}, 50);

const loop = monitorEventLoopDelay({ resolution: 20 });
loop.enable();
setInterval(() => {
  emit({ event: 'stats', online: bots.filter(b => b.ready && !b.ended).length, errors,
    eventLoopP95Ms: loop.percentile(95) / 1e6, eventLoopMaxMs: loop.max / 1e6,
    bytes: bots.reduce((s, b) => s + b.bytes, 0), chunks: bots.reduce((s, b) => s + b.chunks, 0) });
  loop.reset();
}, 5000);

function finish(code) {
  if (finishing) return;
  finishing = true;
  emit({ event: 'result', errors, bots: bots.map(({ name, chunks, bytes, ended }) => ({ name, chunks, bytes, ended })) });
  for (const b of bots) b.client?.end('bench complete');
  setTimeout(() => process.exit(code), 1000);
}
process.on('SIGTERM', () => finish(0));
process.on('SIGINT', () => finish(0));

(async () => {
  while (!finishing) {
    try { control = JSON.parse(fs.readFileSync(controlFile, 'utf8')); } catch { /* keep the last control */ }
    if (control.stop) return finish(0);
    for (const b of bots) {
      if (b.index > control.count && !b.ended) { b.ended = true; emit({ event: 'plannedDrop', name: b.name }); b.client.end('bench step'); }
    }
    // This shard owns indexes shard+1, shard+1+shards, ...
    const wanted = Math.ceil(Math.max(0, control.count - shard) / shards);
    if (bots.length < wanted) {
      const index = bots.length * shards + shard + 1;
      try { await connect(index); } catch (e) { emit({ event: 'joinFailed', index, message: e.message }); }
      await sleep(JOIN_SPACING_MS);
    } else {
      await sleep(250);
    }
  }
})();
