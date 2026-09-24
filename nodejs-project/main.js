// WA Gateway - Node/Baileys engine (Phase 1.1)
// Link, send, always-online, auto-read, auto-reply, anti-delete (all msgs), message log.

const fs = require('fs')
const path = require('path')
const crypto = require('crypto')
let CryptoJS = null
try { CryptoJS = require('crypto-js') } catch (e) {}
const https = require('https')
const express = require('express')
const pino = require('pino')
const QRCode = require('qrcode')
const {
  default: makeWASocket,
  useMultiFileAuthState,
  fetchLatestBaileysVersion,
  DisconnectReason,
  Browsers,
  downloadMediaMessage,
} = require('@whiskeysockets/baileys')

const PORT = parseInt(process.env.WAGW_PORT || '8765', 10)
const AUTH_DIR = process.env.WAGW_AUTH_DIR || './auth'
const SETTINGS_FILE = path.join(path.dirname(AUTH_DIR), 'settings.json')
const MEDIA_DIR = process.env.WAGW_MEDIA_DIR || path.join(path.dirname(AUTH_DIR), 'media')
const MESSAGES_FILE = path.join(path.dirname(AUTH_DIR), 'messages.json')
const NAMES_FILE = path.join(path.dirname(AUTH_DIR), 'names.json')
const STATUS_FILE = path.join(path.dirname(AUTH_DIR), 'statuses.json')

const logger = pino({ level: 'warn' })

let sock = null
let state = null
let saveCreds = null
let currentQr = null
let pairingCode = null
let pairingNumber = null
let presenceTimer = null

let status = { connection: 'close', registered: false, me: null, lastError: null }

let settings = {
  alwaysOnline: false,
  autoRead: false,
  autoReplyEnabled: false,
  autoReplyRules: [],
  aiReplyEnabled: false,
  aiApiUrl: 'https://api.groq.com/openai/v1/chat/completions',
  aiApiKey: '',
  aiModel: 'openai/gpt-oss-20b',
  groupAiEnabled: false,
  aiSystemPrompt: '',
  saveMedia: false,
  hideStatusRead: true,
  stayOffline: false,
}

// stores for anti-delete + history (in-memory; reset on app restart)
const msgStore = new Map()  // id -> { chat, fromMe, text, ts }
const rawStore = new Map()  // id -> { key, message } for native quoted replies
let deletedList = []        // [{ chat, fromMe, text, ts }]
let msgLog = []             // [{ chat, fromMe, text, ts }]
const chatHistory = new Map()  // jid -> [{ role:'user'|'assistant', content }] for AI context
const dpCache = new Map()      // jid -> profile picture url (or null)
const contacts = new Map()     // jid -> { name, notify }
const presences = new Map()
const statusViewers = {}  // statusId -> Set of viewer jids    // jid -> { presence, lastSeen }
let statuses = []              // status@broadcast items (newest first)
try {
  const loaded = JSON.parse(fs.readFileSync(STATUS_FILE, 'utf8'))
  const cutoff = Date.now() - 24 * 3600 * 1000
  if (Array.isArray(loaded)) statuses = loaded.filter(x => x && x.ts && x.ts > cutoff)
} catch (_) { statuses = [] }
let _statusTimer = null
function saveStatusesDebounced() {
  if (_statusTimer) return
  _statusTimer = setTimeout(() => { _statusTimer = null; try { fs.writeFileSync(STATUS_FILE, JSON.stringify(statuses.slice(0, 120))) } catch (_) {} }, 1500)
}

function pushHistory(jid, role, content) {
  if (!jid || !content) return
  let arr = chatHistory.get(jid) || []
  arr.push({ role, content })
  if (arr.length > 20) arr = arr.slice(-20)  // keep last 20 messages
  chatHistory.set(jid, arr)
}

function log(...a) { console.log('[wagw]', ...a) }
const delay = (ms) => new Promise(r => setTimeout(r, ms))

function loadSettings() {
  try { settings = Object.assign(settings, JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'))) } catch (_) {}
}
function saveSettings() {
  try {
    fs.mkdirSync(path.dirname(SETTINGS_FILE), { recursive: true })
    fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2))
  } catch (e) { log('saveSettings err', e?.message) }
}

let saveMsgTimer = null
let nameStore = {}
try { nameStore = JSON.parse(fs.readFileSync(NAMES_FILE, 'utf8')) } catch (_) { nameStore = {} }
let _namesTimer = null
function saveNamesDebounced() {
  if (_namesTimer) return
  _namesTimer = setTimeout(() => { _namesTimer = null; try { fs.writeFileSync(NAMES_FILE, JSON.stringify(nameStore)) } catch (_) {} }, 1500)
}
function rememberName(jid, name) {
  if (jid && name && String(name).trim() && nameStore[jid] !== String(name).trim()) { nameStore[jid] = String(name).trim(); saveNamesDebounced() }
}

function saveMessagesDebounced() {
  if (saveMsgTimer) return
  saveMsgTimer = setTimeout(() => {
    saveMsgTimer = null
    try { fs.writeFileSync(MESSAGES_FILE, JSON.stringify(msgLog.slice(0, 500))) }
    catch (e) { log('saveMsg err', e?.message) }
  }, 2000)
}
function loadMessages() {
  try {
    const arr = JSON.parse(fs.readFileSync(MESSAGES_FILE, 'utf8'))
    if (Array.isArray(arr)) {
      msgLog = arr
      for (const e of arr) if (e && e.id) msgStore.set(e.id, e)
      log('loaded', arr.length, 'messages from disk')
    }
  } catch (_) {}
}

async function loadAuth() {
  const auth = await useMultiFileAuthState(AUTH_DIR)
  state = auth.state
  saveCreds = auth.saveCreds
  status.registered = !!state.creds.registered
  log('auth loaded from', AUTH_DIR, 'registered=', status.registered)
}

function extractText(m) {
  if (!m) return ''
  return m.conversation
    || m.extendedTextMessage?.text
    || m.imageMessage?.caption
    || m.videoMessage?.caption
    || m.documentMessage?.caption
    || m.buttonsMessage?.contentText
    || m.buttonsMessage?.headerText
    || m.templateMessage?.hydratedTemplate?.hydratedContentText
    || m.templateMessage?.hydratedFourRowTemplate?.hydratedContentText
    || m.templateMessage?.fourRowTemplate?.content?.text
    || m.interactiveMessage?.body?.text
    || m.interactiveMessage?.header?.title
    || m.interactiveResponseMessage?.body?.text
    || m.listMessage?.description
    || m.listMessage?.title
    || m.productMessage?.product?.title
    || m.productMessage?.body?.text
    || m.buttonsResponseMessage?.selectedDisplayText
    || m.templateButtonReplyMessage?.selectedDisplayText
    || m.listResponseMessage?.title
    || m.contactMessage?.displayName
    || m.locationMessage?.name
    || m.pollCreationMessage?.name
    || m.eventMessage?.name
    || ''
}

function resolveName(msg, jid) {
  const c = contacts.get(jid)
  let n = msg.pushName || msg.verifiedBizName || (c && c.name) || nameStore[jid] || ''
  if (!n && jid && jid.endsWith('@lid') && sock) {
    try {
      const lm = sock.signalRepository && sock.signalRepository.lidMapping
      const pn = lm && lm.getPNForLID && lm.getPNForLID(jid)
      if (pn) { const c2 = contacts.get(pn); n = (c2 && c2.name) || nameStore[pn] || ''; if (n) { rememberName(jid, n) } }
    } catch (_) {}
  }
  if (n && !(msg.key && msg.key.fromMe)) rememberName(jid, n)
  return n
}

function matchReply(text) {
  const t = (text || '').toLowerCase().trim()
  for (const r of settings.autoReplyRules) {
    const m = (r.match || '').toLowerCase().trim()
    if (!m) continue
    const mode = r.mode || 'contains'
    if (mode === 'exact' && t === m) return r.reply
    if (mode === 'starts' && t.startsWith(m)) return r.reply
    if (mode === 'contains' && t.includes(m)) return r.reply
  }
  return null
}

// OpenAI-compatible chat completion (Groq / OpenRouter / etc.)
async function aiReply(jid) {
  if (!settings.aiReplyEnabled || !settings.aiApiUrl || !settings.aiApiKey) return null
  const history = chatHistory.get(jid) || []
  try {
    const res = await fetch(settings.aiApiUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + settings.aiApiKey },
      body: JSON.stringify({
        model: settings.aiModel || 'openai/gpt-oss-20b',
        messages: [
          ...(settings.aiSystemPrompt ? [{ role: 'system', content: settings.aiSystemPrompt }] : []),
          ...history,
        ],
      }),
    })
    if (!res.ok) { log('ai http', res.status, (await res.text()).slice(0, 200)); return null }
    const data = await res.json()
    const out = data?.choices?.[0]?.message?.content
    return out ? String(out).trim() : null
  } catch (e) { log('ai err', e?.message); return null }
}

const groupDaily = new Map()  // groupJid -> { date, count }
function _today() { return new Date().toISOString().slice(0, 10) }
function groupDailyCount(jid) { const e = groupDaily.get(jid); return (!e || e.date !== _today()) ? 0 : e.count }
function incGroupDaily(jid) { const d = _today(); const e = groupDaily.get(jid); if (!e || e.date !== d) groupDaily.set(jid, { date: d, count: 1 }); else e.count++ }

async function groupAiReply(jid) {
  if (!settings.aiApiUrl || !settings.aiApiKey) return null
  const history = (chatHistory.get(jid) || []).slice(-9)
  const sys = 'You are a friendly, witty member of a WhatsApp group chat (not a formal assistant). Reply briefly and naturally like a real person would, in 1-2 short sentences. CRITICAL: read the recent messages and detect the language and script the group is using — Hindi (Devanagari), Hinglish (Roman Hindi), Bengali (Bangla script), English, etc. — and reply in that SAME language and script. Never say you are an AI or a bot. Be relevant to what was just said, warm and casual.'
  try {
    const res = await fetch(settings.aiApiUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + settings.aiApiKey },
      body: JSON.stringify({ model: settings.aiModel || 'openai/gpt-oss-20b', messages: [{ role: 'system', content: sys }, ...history] }),
    })
    if (!res.ok) { log('group ai http', res.status, (await res.text()).slice(0, 150)); return null }
    const data = await res.json()
    const out = data?.choices?.[0]?.message?.content
    return out ? String(out).trim() : null
  } catch (e) { log('group ai err', e?.message); return null }
}

function remember(id, entry) {
  if (id) {
    entry.id = id
    msgStore.set(id, entry)
    if (msgStore.size > 1500) { const k = msgStore.keys().next().value; msgStore.delete(k) }
  }
  msgLog.unshift(entry)
  if (msgLog.length > 500) msgLog = msgLog.slice(0, 500)
  saveMessagesDebounced()
}

function applyPresence() {
  if (presenceTimer) { clearInterval(presenceTimer); presenceTimer = null }
  if (!sock || status.connection !== 'open') return
  if (settings.stayOffline) {
    // Never broadcast "online" — keeps the account appearing offline (freeze last seen).
    const tick = () => { try { sock.sendPresenceUpdate('unavailable') } catch (_) {} }
    tick()
    presenceTimer = setInterval(tick, 10000)
  } else if (settings.alwaysOnline) {
    const tick = () => { try { sock.sendPresenceUpdate('available') } catch (_) {} }
    tick()
    presenceTimer = setInterval(tick, 10000)
  }
}

function handleConnUpdate(u) {
  const { connection, lastDisconnect, qr } = u
  if (qr) { currentQr = qr; log('qr updated') }
  if (connection) status.connection = connection

  if (connection === 'open') {
    status.registered = true
    status.me = sock?.user?.id || null
    status.lastError = null
    currentQr = null; pairingCode = null; pairingNumber = null
    log('CONNECTED as', status.me)
    applyPresence()
  }

  if (connection === 'close') {
    const code = lastDisconnect?.error?.output?.statusCode
    status.lastError = code || (lastDisconnect?.error?.message ?? 'closed')
    const alreadyLinked = !!state?.creds?.registered
    log('closed. code=', code, 'alreadyLinked=', alreadyLinked)

    if (code === DisconnectReason.loggedOut) {
      currentQr = null; pairingCode = null; pairingNumber = null
      if (alreadyLinked) {
        status.registered = false
      } else {
        log('401 while unlinked -> wiping auth, restarting clean')
        try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }) } catch (_) {}
        state = null; sock = null
        setTimeout(async () => { await loadAuth(); startSocket().catch(e => log('fresh start err', e?.message)) }, 5000)
      }
    } else {
      const wait = alreadyLinked ? 2000 : 8000
      setTimeout(() => startSocket().catch(e => log('reconnect err', e?.message)), wait)
    }
  }
}

function captureDelete(delId) {
  const orig = delId ? msgStore.get(delId) : null
  if (orig && !orig.deleted) {
    orig.deleted = true // same object lives in msgLog too -> marked in place, no duplicate
    saveMessagesDebounced()
    log('marked deleted:', JSON.stringify(orig.text))
  }
}

function mediaKind(m) {
  if (m.imageMessage) return ['image', m.imageMessage, 'jpg']
  if (m.videoMessage) return ['video', m.videoMessage, 'mp4']
  if (m.audioMessage) return ['audio', m.audioMessage, 'ogg']
  if (m.stickerMessage) return ['sticker', m.stickerMessage, 'webp']
  if (m.documentMessage) {
    const fn = m.documentMessage.fileName || ''
    const ext = fn.includes('.') ? fn.split('.').pop() : 'bin'
    return ['document', m.documentMessage, ext]
  }
  return null
}

function quotedOf(message) {
  if (!message) return null
  for (const k of Object.keys(message)) {
    const ci = message[k] && message[k].contextInfo
    if (ci && ci.quotedMessage) return { text: extractText(ci.quotedMessage) || '[media]', sender: ci.participant || '' }
  }
  return null
}

function unwrapInner(message) {
  let m = message
  for (let i = 0; i < 5 && m; i++) {
    if (m.ephemeralMessage?.message) { m = m.ephemeralMessage.message; continue }
    if (m.viewOnceMessage?.message) { m = m.viewOnceMessage.message; continue }
    if (m.viewOnceMessageV2?.message) { m = m.viewOnceMessageV2.message; continue }
    if (m.viewOnceMessageV2Extension?.message) { m = m.viewOnceMessageV2Extension.message; continue }
    if (m.deviceSentMessage?.message) { m = m.deviceSentMessage.message; continue }
    if (m.documentWithCaptionMessage?.message) { m = m.documentWithCaptionMessage.message; continue }
    break
  }
  if (m?.imageMessage) m.imageMessage.viewOnce = false
  if (m?.videoMessage) m.videoMessage.viewOnce = false
  return m
}

async function enrichMedia(msg, entry) {
  const kind = mediaKind(msg.message)
  if (!kind) return
  const [type, node, ext] = kind
  const name = (msg.key.id || Date.now()) + '.' + ext
  const thumb = node.jpegThumbnail ? Buffer.from(node.jpegThumbnail).toString('base64') : null
  const caption = node.caption || node.fileName || ''
  entry.media = { name, type, thumb, saved: false }
  if (!entry.text) entry.text = caption
  if (settings.saveMedia || type === 'image' || type === 'video' || type === 'sticker') {
    try {
      const buf = await downloadMediaMessage(msg, 'buffer', {}, { logger, reuploadRequest: sock.updateMediaMessage })
      fs.mkdirSync(MEDIA_DIR, { recursive: true })
      fs.writeFileSync(path.join(MEDIA_DIR, name), buf)
      entry.media.saved = true
      log('media saved', name, type)
    } catch (e) { log('media dl err', e?.message) }
  }
}

async function handleMessages({ messages, type }) {
  for (const msg of messages || []) {
    try {
      if (!msg.message) continue
      msg.message = unwrapInner(msg.message) || msg.message
      const from = msg.key.remoteJid
      const fromMe = !!msg.key.fromMe
      const id = msg.key.id
      const sender = msg.key.participant || '' // group: who sent it

      // standalone reaction arrives via messages.reaction; don't show as a message
      if (msg.message.reactionMessage) continue
      // delete-for-everyone -> protocol REVOKE
      const proto = msg.message.protocolMessage
      if (proto && (proto.type === 0 || proto.type === 'REVOKE')) {
        captureDelete(proto.key?.id)
        continue
      }

      if (!from) continue
      if (from === 'status@broadcast') {
        const sndr = fromMe ? ((sock && sock.user && sock.user.id) || 'me') : (msg.key.participant || msg.participant)
        if (sndr) {
          const sTs = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
          const sEntry = { sender: sndr, name: fromMe ? 'My Status' : (msg.pushName || ''), mine: fromMe, id: msg.key.id, text: extractText(msg.message), ts: sTs }
          try { await enrichMedia(msg, sEntry) } catch (_) {}
          statuses.unshift(sEntry)
          if (statuses.length > 120) statuses.length = 120
          saveStatusesDebounced()
        }
        continue
      }
      const ts = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
      const entry = { chat: from, name: resolveName(msg, from), sender, fromMe, text: extractText(msg.message), ts, id }
      entry.quoted = quotedOf(msg.message)
      await enrichMedia(msg, entry)
      const text = entry.text

      // store EVERY message (both directions) for anti-delete + history
      remember(id, entry)
      if (id) { rawStore.set(id, { key: msg.key, message: msg.message }); if (rawStore.size > 400) { const rk = rawStore.keys().next().value; rawStore.delete(rk) } }

      // auto-read + auto-reply: only incoming personal chats
      const isPersonal = from.endsWith('@s.whatsapp.net') || from.endsWith('@lid')
      if (!fromMe && isPersonal) {
        if (settings.autoRead) {
          try { await sock.readMessages([msg.key]); log('auto-read ok') }
          catch (e) { log('auto-read err', e?.message) }
        }
        if (text) {
          pushHistory(from, 'user', text)
          let reply = null
          if (settings.autoReplyEnabled) reply = matchReply(text)
          if (!reply && settings.aiReplyEnabled) reply = await aiReply(from)
          if (reply) {
            try {
              await sock.sendMessage(from, { text: reply })
              pushHistory(from, 'assistant', reply)
              log('auto-reply sent:', reply)
            } catch (e) { log('auto-reply err', e?.message) }
          }
        }
      }

      // group AI: /ai command (always) or auto-reply on greeting/question (max 10/day/group)
      const isGroup = from.endsWith('@g.us')
      if (!fromMe && isGroup && text) {
        const t = text.trim()
        const lower = t.toLowerCase()
        let explicit = false, auto = false
        if (lower.startsWith('/ai')) {
          const qq = t.replace(/^\/ai\s*/i, '').trim()
          pushHistory(from, 'user', qq || t)
          if (qq) explicit = true
        } else {
          pushHistory(from, 'user', t)
          if (settings.groupAiEnabled && groupDailyCount(from) < 10) {
            const isGreeting = /^(hi+|he+y+|he+llo+|helo|namaste|namaskar|hola|salaam|assalam|yo|sup)\b/i.test(t)
            const isQuestion = t.includes('?') || /^(what|why|how|when|who|where|which|kya|kaise|kyu|kyun|kaun|kab|kahan|kitna|ki|ke|bolo|batao)\b/i.test(t)
            if (isGreeting || isQuestion) auto = true
          }
        }
        if (explicit || auto) {
          try { await sock.readMessages([msg.key]) } catch (_) {}
          const reply = await groupAiReply(from)
          if (reply) {
            try { await sock.sendMessage(from, { text: reply }); pushHistory(from, 'assistant', reply); if (auto) incGroupDaily(from); log('group ai sent (' + (explicit ? 'cmd' : 'auto ' + groupDailyCount(from) + '/10') + ')') }
            catch (e) { log('group ai send err', e?.message) }
          }
        }
      }
    } catch (e) { log('handleMessages err', e?.message) }
  }
}

// also detect deletions that arrive as an update instead of a new message
function handleUpdates(updates) {
  for (const u of updates || []) {
    try {
      const upd = u.update || {}
      const proto = upd.message?.protocolMessage
      if ((proto && (proto.type === 0 || proto.type === 'REVOKE'))) {
        captureDelete(proto.key?.id || u.key?.id)
      } else if (upd.messageStubType === 1 /* REVOKE stub */) {
        captureDelete(u.key?.id)
      }
    } catch (_) {}
  }
}

// history sync on link -> fill the message log
async function handleHistory({ messages, contacts: cts }) {
  if (cts) for (const c of cts) { if (c.id) { const nm = c.name || c.notify || ''; contacts.set(c.id, { name: nm, notify: c.notify || '' }); rememberName(c.id, nm) } }
  let n = 0
  for (const msg of messages || []) {
    try {
      if (!msg.message) continue
      msg.message = unwrapInner(msg.message) || msg.message
      const from = msg.key.remoteJid
      if (!from) continue
      if (from === 'status@broadcast') {
        const sndr2 = msg.key.fromMe ? ((sock && sock.user && sock.user.id) || 'me') : (msg.key.participant || msg.participant)
        if (sndr2) {
          const sTs2 = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
          const sE = { sender: sndr2, name: msg.key.fromMe ? 'My Status' : (msg.pushName || ''), mine: !!msg.key.fromMe, id: msg.key.id, text: extractText(msg.message), ts: sTs2 }
          try { await enrichMedia(msg, sE) } catch (_) {}
          if (!statuses.find(x => x.sender === sE.sender && x.ts === sE.ts)) { statuses.unshift(sE); if (statuses.length > 120) statuses.length = 120; saveStatusesDebounced() }
        }
        continue
      }
      const fromMe = !!msg.key.fromMe
      const ts = msg.messageTimestamp ? Number(msg.messageTimestamp) * 1000 : Date.now()
      const entry = { chat: from, name: resolveName(msg, from), fromMe, text: extractText(msg.message), ts, id: msg.key.id }
      if (msg.key.id) msgStore.set(msg.key.id, entry)
      msgLog.push(entry)
      n++
    } catch (_) {}
  }
  msgLog.sort((a, b) => b.ts - a.ts)
  if (msgLog.length > 500) msgLog = msgLog.slice(0, 500)
  log('history.set added', n)
}

async function startSocket() {
  let version
  try { const v = await fetchLatestBaileysVersion(); version = v.version; log('WA version', version.join('.')) }
  catch (e) { log('version fetch failed, using bundled') }

  sock = makeWASocket({
    version,
    auth: state,
    logger,
    printQRInTerminal: false,
    browser: Browsers.ubuntu('imayx.in'),
    markOnlineOnConnect: settings.alwaysOnline && !settings.stayOffline,
    syncFullHistory: false,
  })
  sock.ev.on('creds.update', saveCreds)
  sock.ev.on('connection.update', handleConnUpdate)
  sock.ev.on('messages.upsert', handleMessages)
  sock.ev.on('messages.update', handleUpdates)
  sock.ev.on('messaging-history.set', handleHistory)
  sock.ev.on('messages.reaction', (reactions) => {
    for (const r of reactions || []) {
      const id = r.key?.id
      const e = id ? msgStore.get(id) : null
      if (e) { e.reaction = r.reaction?.text || ''; saveMessagesDebounced() }
    }
  })
  const addContacts = (list) => { for (const c of list || []) { if (c.id) { const nm = c.name || c.notify || ''; contacts.set(c.id, { name: nm, notify: c.notify || '' }); rememberName(c.id, nm) } } }
  sock.ev.on('contacts.upsert', addContacts)
  sock.ev.on('contacts.update', addContacts)
  sock.ev.on('contacts.set', ({ contacts: cs }) => addContacts(cs))
  sock.ev.on('message-receipt.update', (updates) => {
    for (const u of updates || []) {
      try {
        const k = u.key || {}
        if (k.remoteJid === 'status@broadcast' && k.fromMe) {
          const id = k.id
          const viewer = (u.receipt && (u.receipt.userJid || u.receipt.receiptTimestamp && u.receipt.userJid)) || k.participant
          if (id && viewer) {
            if (!statusViewers[id]) statusViewers[id] = new Set()
            statusViewers[id].add(viewer)
          }
        }
      } catch (_) {}
    }
  })
  sock.ev.on('presence.update', ({ id, presences: p }) => {
    if (!id || !p) return
    const first = Object.values(p)[0]
    if (first) presences.set(id, { presence: first.lastKnownPresence || 'unavailable', lastSeen: first.lastSeen || null })
  })
}

const app = express()
app.use(express.json({ limit: '64mb' }))

app.get('/health', (req, res) => res.json({ ok: true, node: process.version }))

app.get('/status', (req, res) => res.json({
  connection: status.connection,
  registered: status.registered,
  me: status.me,
  lastError: status.lastError,
  hasQr: !!currentQr,
  pairingCode: pairingCode,
}))

app.get('/qr.png', async (req, res) => {
  if (!currentQr) return res.status(204).end()
  try {
    const buf = await QRCode.toBuffer(currentQr, { type: 'png', width: 512, margin: 1 })
    res.setHeader('Content-Type', 'image/png'); res.setHeader('Cache-Control', 'no-store'); res.send(buf)
  } catch (e) { res.status(500).end() }
})

app.post('/pair', async (req, res) => {
  try {
    const number = String(req.body?.number || '').replace(/\D/g, '')
    if (state?.creds?.registered) return res.json({ registered: true })
    if (!number) return res.status(400).json({ error: 'number required' })
    if (pairingCode && pairingNumber === number && sock) return res.json({ code: pairingCode })
    try { await sock?.ws?.close() } catch (_) {}
    try { fs.rmSync(AUTH_DIR, { recursive: true, force: true }) } catch (_) {}
    state = null; sock = null; currentQr = null; pairingCode = null; pairingNumber = null
    await loadAuth(); await startSocket(); await delay(3000)
    pairingCode = await sock.requestPairingCode(number)
    pairingNumber = number
    log('pairing code', pairingCode)
    res.json({ code: pairingCode })
  } catch (e) { log('pair error', e?.message); res.status(500).json({ error: e?.message || 'pair failed' }) }
})

app.post('/send', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.status(409).json({ error: 'not connected' })
    const to = String(req.body?.to || '').replace(/\D/g, '')
    const text = String(req.body?.text || '')
    if (!to || !text) return res.status(400).json({ error: 'to and text required' })
    const r = await sock.sendMessage(to + '@s.whatsapp.net', { text })
    res.json({ ok: true, id: r?.key?.id || null })
  } catch (e) { log('send error', e?.message); res.status(500).json({ error: e?.message || 'send failed' }) }
})

app.get('/presence', async (req, res) => {
  const jid = String(req.query.jid || '')
  if (!jid || !sock) return res.json({ presence: 'unavailable', lastSeen: null })
  try { await sock.presenceSubscribe(jid) } catch (_) {}
  const p = presences.get(jid) || { presence: 'unavailable', lastSeen: null }
  res.json(p)
})

app.get('/session/export', (req, res) => {
  try {
    const out = { auth: {}, messages: null, settings: null }
    try { for (const f of fs.readdirSync(AUTH_DIR)) out.auth[f] = fs.readFileSync(path.join(AUTH_DIR, f), 'utf8') } catch (_) {}
    try { out.messages = fs.readFileSync(MESSAGES_FILE, 'utf8') } catch (_) {}
    try { out.settings = fs.readFileSync(SETTINGS_FILE, 'utf8') } catch (_) {}
    res.json(out)
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/session/import', async (req, res) => {
  try {
    const b = req.body || {}
    try { await sock?.ws?.close() } catch (_) {}
    fs.mkdirSync(AUTH_DIR, { recursive: true })
    if (b.auth) for (const [f, content] of Object.entries(b.auth)) fs.writeFileSync(path.join(AUTH_DIR, path.basename(f)), content)
    if (b.messages) fs.writeFileSync(MESSAGES_FILE, b.messages)
    if (b.settings) fs.writeFileSync(SETTINGS_FILE, b.settings)
    state = null; sock = null; msgStore.clear(); rawStore.clear(); msgLog = []
    loadSettings(); loadMessages(); await loadAuth(); await startSocket()
    res.json({ ok: true, registered: status.registered })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.get('/contacts', (req, res) => {
  const items = []
  const seen = new Set()
  for (const [jid, c] of contacts) {
    if (!jid.endsWith('@s.whatsapp.net')) continue
    items.push({ jid, name: c.name || c.notify || nameStore[jid] || '', number: jid.split('@')[0] })
    seen.add(jid)
  }
  for (const jid of Object.keys(nameStore)) {
    if (seen.has(jid) || !nameStore[jid]) continue
    items.push({ jid, name: nameStore[jid], number: jid.split('@')[0].split(':')[0] })
  }
  items.sort((a, b) => (a.name || a.number).localeCompare(b.name || b.number))
  res.json({ items })
})

app.post('/message/delete', async (req, res) => {
  try {
    const jid = String(req.body?.jid || '')
    const id = String(req.body?.id || '')
    const forEveryone = !!req.body?.forEveryone
    const fromMe = !!req.body?.fromMe
    const text = req.body?.text
    const ts = Number(req.body?.ts || 0)
    if (!jid) return res.status(400).json({ error: 'jid required' })
    if (forEveryone && sock && id) { try { await sock.sendMessage(jid, { delete: { remoteJid: jid, id, fromMe } }) } catch (_) {} }
    if (id) { msgLog = msgLog.filter(m => m.id !== id); msgStore.delete(id) }
    else if (text != null) { msgLog = msgLog.filter(m => !(m.text === text && Math.abs((m.ts || 0) - ts) < 6000)) }
    saveMessagesDebounced()
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/chat/delete', (req, res) => {
  const jid = String(req.body?.jid || '')
  if (!jid) return res.status(400).json({ error: 'jid required' })
  msgLog = msgLog.filter(m => m.chat !== jid)
  for (const [k, v] of msgStore) if (v.chat === jid) msgStore.delete(k)
  chatHistory.delete(jid)
  saveMessagesDebounced()
  res.json({ ok: true })
})

app.post('/react', async (req, res) => {
  try {
    const jid = String(req.body?.jid || '')
    const id = String(req.body?.id || '')
    const emoji = String(req.body?.emoji || '')
    const fromMe = !!req.body?.fromMe
    if (!sock || !jid || !id) return res.status(400).json({ error: 'jid,id required' })
    await sock.sendMessage(jid, { react: { text: emoji, key: { remoteJid: jid, id, fromMe } } })
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/block', async (req, res) => {
  try {
    const jid = String(req.body?.jid || '')
    const action = String(req.body?.action || 'block')
    if (!sock || !jid) return res.status(400).json({ error: 'jid required' })
    await sock.updateBlockStatus(jid, action === 'unblock' ? 'unblock' : 'block')
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/profile/name', async (req, res) => {
  try {
    const name = String(req.body?.name || '').trim()
    if (!sock || !name) return res.status(400).json({ error: 'name required' })
    await sock.updateProfileName(name)
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/profile/bio', async (req, res) => {
  try {
    const bio = String(req.body?.bio || '')
    if (!sock) return res.status(409).json({ error: 'not connected' })
    await sock.updateProfileStatus(bio)
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/profile/picture', async (req, res) => {
  try {
    const b64 = String(req.body?.data || '')
    if (!sock || !b64 || !sock.user?.id) return res.status(400).json({ error: 'data required' })
    await sock.updateProfilePicture(sock.user.id, Buffer.from(b64, 'base64'))
    dpCache.delete(sock.user.id)
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/sendmedia', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.status(409).json({ error: 'not connected' })
    const jid = String(req.body?.jid || '')
    const type = String(req.body?.type || 'document')
    const b64 = String(req.body?.data || '')
    const filename = String(req.body?.filename || 'file')
    const caption = String(req.body?.caption || '')
    if (!jid || !b64) return res.status(400).json({ error: 'jid and data required' })
    const buf = Buffer.from(b64, 'base64')
    let content
    if (type === 'image') content = { image: buf, caption }
    else if (type === 'video') content = { video: buf, caption }
    else if (type === 'audio') content = { audio: buf, mimetype: 'audio/mp4' }
    else content = { document: buf, fileName: filename, mimetype: 'application/octet-stream', caption }
    const r = await sock.sendMessage(jid, content)
    res.json({ ok: true, id: r?.key?.id || null })
  } catch (e) { log('sendmedia error', e?.message); res.status(500).json({ error: e?.message || 'send failed' }) }
})

app.get('/dp', async (req, res) => {
  const jid = String(req.query.jid || '')
  if (!jid || !sock) return res.status(404).end()
  try {
    if (!dpCache.has(jid)) {
      const url = await sock.profilePictureUrl(jid, 'image').catch(() => null)
      dpCache.set(jid, url || null)
    }
    const url = dpCache.get(jid)
    if (!url) return res.status(404).end()
    const r = await fetch(url)
    if (!r.ok) return res.status(404).end()
    const buf = Buffer.from(await r.arrayBuffer())
    res.setHeader('Content-Type', 'image/jpeg')
    res.setHeader('Cache-Control', 'max-age=3600')
    res.send(buf)
  } catch (e) { res.status(404).end() }
})

function httpGet(url, headers) {
  return new Promise((resolve, reject) => {
    try {
      const req = https.get(url, { headers: headers || { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }, rejectUnauthorized: false, timeout: 15000 }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume(); return httpGet(res.headers.location, headers).then(resolve).catch(reject)
        }
        let data = ''
        res.on('data', c => data += c)
        res.on('end', () => resolve(data))
      })
      req.on('error', reject)
      req.on('timeout', () => { req.destroy(); reject(new Error('timeout')) })
    } catch (e) { reject(e) }
  })
}

function saavnDecrypt(encUrl) {
  // Try pure-JS DES first (OpenSSL 3 dropped DES from default provider)
  if (CryptoJS) {
    try {
      const key = CryptoJS.enc.Utf8.parse('38346591')
      const ct = CryptoJS.enc.Base64.parse(encUrl)
      const dec = CryptoJS.DES.decrypt({ ciphertext: ct }, key, { mode: CryptoJS.mode.ECB, padding: CryptoJS.pad.Pkcs7 })
      const url = dec.toString(CryptoJS.enc.Utf8)
      if (url && url.startsWith('http')) return url.replace('_96.mp4', '_320.mp4')
    } catch (e) {}
  }
  // Fallback: native crypto (may work if legacy provider available)
  try {
    const key = Buffer.from('38346591', 'utf8')
    const encrypted = Buffer.from(encUrl, 'base64')
    const decipher = crypto.createDecipheriv('des-ecb', key, null)
    decipher.setAutoPadding(true)
    const out = decipher.update(encrypted, undefined, 'utf8') + decipher.final('utf8')
    return out.replace('_96.mp4', '_320.mp4')
  } catch (e) { return null }
}
function deEnt(str) {
  return String(str || '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#039;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')
}
app.get('/music/search', async (req, res) => {
  const diag = []
  try {
    const q = String(req.query.q || '').trim()
    if (!q) return res.json({ items: [] })
    let items = []
    // JioSaavn official API (direct) + DES decrypt
    try {
      const url = 'https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&q=' + encodeURIComponent(q) + '&p=1&n=30'
      const body = await httpGet(url, { 'User-Agent': 'Mozilla/5.0 (Linux; Android 12)', 'Accept': 'application/json', 'Referer': 'https://www.jiosaavn.com/' })
      let data
      try { data = JSON.parse(body) } catch (pe) { data = JSON.parse(body.replace(/^[^{\[]*/, '')) }
      const results = (data && data.results) ? data.results : []
      diag.push('results=' + results.length)
      let noMedia = 0, noDec = 0
      for (const it of results) {
        const mi = it.more_info || {}
        const enc = mi.encrypted_media_url || it.encrypted_media_url
        if (!enc) { noMedia++; continue }
        const audio = saavnDecrypt(enc)
        if (!audio) { noDec++; continue }
        let artist = ''
        try { artist = (mi.artistMap && mi.artistMap.primary_artists ? mi.artistMap.primary_artists.map(a => a.name).join(', ') : '') || it.primary_artists || it.subtitle || '' } catch (_) {}
        items.push({ title: deEnt(it.title || it.song || ''), artist: deEnt(artist), image: String(it.image || '').replace('150x150', '500x500'), url: audio })
      }
      diag.push('playable=' + items.length + (noMedia ? ' noMedia=' + noMedia : '') + (noDec ? ' noDec=' + noDec : ''))
    } catch (e) { diag.push('jio_err=' + (e && e.message)) }
    log('music "' + q + '" ' + diag.join(' | '))
    res.json({ items: items.slice(0, 25), error: items.length === 0 ? diag.join(' | ') : '' })
  } catch (e) { res.json({ items: [], error: (e && e.message) + ' | ' + diag.join('|') }) }
})

app.get('/music/lyrics', async (req, res) => {
  try {
    const rawTitle = String(req.query.title || '').trim()
    const artist = String(req.query.artist || '').trim()
    if (!rawTitle) return res.json({ synced: '', plain: '' })
    const title = rawTitle.replace(/\(.*?\)/g, '').replace(/\[.*?\]/g, '').replace(/\bfrom\b.*$/i, '').replace(/\s*-\s*.*$/, '').trim() || rawTitle
    const firstArtist = artist.split(',')[0].trim()
    let data = {}
    try {
      const body = await httpGet('https://lrclib.net/api/get?track_name=' + encodeURIComponent(title) + '&artist_name=' + encodeURIComponent(firstArtist), { 'User-Agent': 'AyXWhatsApp/1.0', 'Accept': 'application/json' })
      data = JSON.parse(body)
    } catch (_) {}
    if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
      try {
        const sbody = await httpGet('https://lrclib.net/api/search?q=' + encodeURIComponent((title + ' ' + firstArtist).trim()), { 'User-Agent': 'AyXWhatsApp/1.0', 'Accept': 'application/json' })
        const arr = JSON.parse(sbody)
        if (Array.isArray(arr) && arr.length) data = arr.find(x => x.syncedLyrics) || arr[0]
      } catch (_) {}
    }
    res.json({ synced: (data && data.syncedLyrics) || '', plain: (data && data.plainLyrics) || '' })
  } catch (e) { res.json({ synced: '', plain: '' }) }
})

app.post('/status/post', async (req, res) => {
  const diag = []
  try {
    if (!sock) return res.status(409).json({ ok: false, error: 'not connected' })
    diag.push('conn=' + status.connection)
    const type = String(req.body?.type || 'image')
    const b64 = String(req.body?.data || '')
    const caption = String(req.body?.caption || '')
    if (!b64) return res.status(400).json({ ok: false, error: 'no data' })
    const buf = Buffer.from(b64, 'base64')
    if (!buf || buf.length === 0) return res.status(400).json({ ok: false, error: 'empty media' })
    diag.push('bytes=' + buf.length)
    const content = type === 'video' ? { video: buf, caption } : { image: buf, caption }
    const audience = String(req.body?.audience || 'all')
    const selJids = Array.isArray(req.body?.jids) ? req.body.jids : []
    const set = new Set()
    for (const j of contacts.keys()) if (j.endsWith('@s.whatsapp.net')) set.add(j)
    for (const m of msgLog) if (m.chat && m.chat.endsWith('@s.whatsapp.net')) set.add(m.chat)
    const all = Array.from(set)
    let jids
    if (audience === 'only') jids = selJids
    else if (audience === 'except') jids = all.filter(j => !selJids.includes(j))
    else jids = all
    try { const meJid = sock?.user?.id?.split(':')[0] + '@s.whatsapp.net'; if (meJid && !jids.includes(meJid)) jids.push(meJid) } catch (_) {}
    diag.push('recipients=' + jids.length)
    if (jids.length === 0) { diag.push('WARN:no-recipients'); }
    const r = await sock.sendMessage('status@broadcast', content, { statusJidList: jids, backgroundColor: '#000000', font: 3 })
    const id = (r && r.key && r.key.id) || null
    diag.push('id=' + id)
    try {
      fs.mkdirSync(MEDIA_DIR, { recursive: true })
      const ext = type === 'video' ? '.mp4' : '.jpg'
      const mediaName = 'own_' + (id || Date.now()) + ext
      fs.writeFileSync(path.join(MEDIA_DIR, mediaName), buf)
      const meJid = (sock && sock.user && sock.user.id) || 'me'
      statuses = statuses.filter(x => !(x.mine && x.id === id))
      statuses.unshift({ sender: meJid, name: 'My Status', mine: true, id, text: caption, ts: Date.now(), mediaName, mediaType: type === 'video' ? 'video' : 'image' })
      if (statuses.length > 120) statuses.length = 120
      saveStatusesDebounced()
    } catch (_) {}
    log('status post ' + diag.join(' '))
    res.json({ ok: !!id, id, recipients: jids.length, diag: diag.join(' ') })
  } catch (e) {
    log('status post ERR ' + (e && e.message) + ' | ' + diag.join(' '))
    res.status(500).json({ ok: false, error: (e && e.message) || 'send failed', diag: diag.join(' ') })
  }
})

app.post('/sendreply', async (req, res) => {
  try {
    const jid = String(req.body?.jid || '')
    const text = String(req.body?.text || '')
    const quotedId = String(req.body?.quotedId || '')
    if (!sock || !jid || !text) return res.status(400).json({ error: 'jid,text required' })
    const quoted = rawStore.get(quotedId)
    try {
      if (quoted && quoted.message) await sock.sendMessage(jid, { text }, { quoted })
      else await sock.sendMessage(jid, { text })
    } catch (e) { await sock.sendMessage(jid, { text }) }
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.post('/sendraw', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.status(409).json({ error: 'not connected' })
    const jid = String(req.body?.jid || '')
    const text = String(req.body?.text || '')
    if (!jid || !text) return res.status(400).json({ error: 'jid and text required' })
    const r = await sock.sendMessage(jid, { text })
    res.json({ ok: true, id: r?.key?.id || null })
  } catch (e) { log('sendraw error', e?.message); res.status(500).json({ error: e?.message || 'send failed' }) }
})

app.get('/settings', (req, res) => res.json(settings))
app.post('/settings', (req, res) => {
  const b = req.body || {}
  if (typeof b.alwaysOnline === 'boolean') settings.alwaysOnline = b.alwaysOnline
  if (typeof b.autoRead === 'boolean') settings.autoRead = b.autoRead
  if (typeof b.hideStatusRead === 'boolean') settings.hideStatusRead = b.hideStatusRead
  if (typeof b.autoReplyEnabled === 'boolean') settings.autoReplyEnabled = b.autoReplyEnabled
  if (Array.isArray(b.autoReplyRules)) settings.autoReplyRules = b.autoReplyRules
  if (typeof b.aiReplyEnabled === 'boolean') settings.aiReplyEnabled = b.aiReplyEnabled
  if (typeof b.aiApiUrl === 'string') settings.aiApiUrl = b.aiApiUrl
  if (typeof b.aiApiKey === 'string') settings.aiApiKey = b.aiApiKey
  if (typeof b.aiModel === 'string') settings.aiModel = b.aiModel
  if (typeof b.groupAiEnabled === 'boolean') settings.groupAiEnabled = b.groupAiEnabled
  if (typeof b.aiSystemPrompt === 'string') settings.aiSystemPrompt = b.aiSystemPrompt
  if (typeof b.saveMedia === 'boolean') settings.saveMedia = b.saveMedia
  if (typeof b.stayOffline === 'boolean') settings.stayOffline = b.stayOffline
  saveSettings(); applyPresence()
  res.json(settings)
})

app.post('/onwhatsapp', async (req, res) => {
  try {
    const nums = Array.isArray(req.body?.numbers) ? req.body.numbers : []
    if (!sock || nums.length === 0) return res.json({ items: [] })
    const out = []
    for (let i = 0; i < nums.length; i += 80) {
      const chunk = nums.slice(i, i + 80).map(n => String(n).replace(/\D/g, '') + '@s.whatsapp.net')
      try {
        const r = await sock.onWhatsApp(...chunk)
        for (const x of (r || [])) {
          if (x && x.exists) {
            const number = String(x.jid).split('@')[0].split(':')[0]
            const lid = x.lid ? String(x.lid).split('@')[0].split(':')[0] : null
            out.push({ number, lid })
          }
        }
      } catch (_) {}
    }
    res.json({ items: out })
  } catch (e) { res.json({ items: [] }) }
})

app.post('/status/delete', async (req, res) => {
  try {
    if (!sock) return res.status(409).json({ error: 'not connected' })
    const id = String(req.body?.id || '')
    if (!id) return res.status(400).json({ error: 'id required' })
    let meJid
    try { meJid = sock?.user?.id ? sock.user.id.split(':')[0] + '@s.whatsapp.net' : undefined } catch (_) {}
    const key = { remoteJid: 'status@broadcast', id, fromMe: true }
    if (meJid) key.participant = meJid
    let ok = false, err = ''
    try { await sock.sendMessage('status@broadcast', { delete: key }); ok = true }
    catch (e) { err = (e && e.message) || 'delete failed' }
    statuses = statuses.filter(x => x.id !== id); saveStatusesDebounced()
    res.json({ ok, error: err })
  } catch (e) { res.status(500).json({ error: e && e.message }) }
})

app.get('/status/viewers', (req, res) => {
  const ids = String(req.query.id || '').split(',').map(x => x.trim()).filter(Boolean)
  const set = new Set()
  for (const id of ids) { const v = statusViewers[id]; if (v) for (const j of v) set.add(j) }
  const out = Array.from(set).map(jid => ({ jid, name: resolveName(jid) }))
  res.json({ viewers: out })
})

app.get('/me', (req, res) => {
  const jid = (sock && sock.user && sock.user.id) ? sock.user.id.split(':')[0] + '@s.whatsapp.net' : ''
  res.json({ jid, name: (sock && sock.user && sock.user.name) || '' })
})
app.get('/statuses', (req, res) => {
  const cutoff = Date.now() - 24 * 3600 * 1000
  statuses = statuses.filter(x => x && x.ts && x.ts > cutoff)
  // flatten media (enrichMedia stores it nested as .media.{name,type,thumb}; own-posts store it flat)
  const items = statuses.slice(0, 120).map(s => ({
    sender: s.sender,
    name: s.name || '',
    text: s.text || '',
    mediaName: s.mediaName || (s.media && s.media.name) || '',
    mediaType: s.mediaType || (s.media && s.media.type) || '',
    thumb: s.thumb || (s.media && s.media.thumb) || '',
    ts: s.ts,
    mine: !!s.mine,
    id: s.id || '',
  }))
  res.json({ items })
})
app.get('/messages', (req, res) => res.json({ items: msgLog.slice(0, 200) }))
app.get('/deleted', (req, res) => res.json({ items: deletedList }))

app.get('/media/:name', (req, res) => {
  const f = path.join(MEDIA_DIR, path.basename(req.params.name))
  if (!fs.existsSync(f)) return res.status(404).end()
  res.sendFile(f)
})

app.post('/logout', async (req, res) => {
  try {
    try { await sock?.logout() } catch (_) {}
    try { await sock?.ws?.close() } catch (_) {}
    fs.rmSync(AUTH_DIR, { recursive: true, force: true })
    sock = null; currentQr = null; pairingCode = null; pairingNumber = null
    msgStore.clear(); rawStore.clear(); deletedList = []; msgLog = []; chatHistory.clear(); dpCache.clear(); presences.clear(); statuses = []
    try { fs.rmSync(MESSAGES_FILE, { force: true }) } catch (_) {}
    status = { connection: 'close', registered: false, me: null, lastError: null }
    await loadAuth(); await startSocket()
    res.json({ ok: true })
  } catch (e) { res.status(500).json({ error: e?.message }) }
})

app.use((req, res) => res.status(404).json({ ok: false, error: 'not found', path: req.path }))
app.use((err, req, res, next) => { log('http err', err?.message); res.status(500).json({ ok: false, error: err?.message || 'server error' }) })

app.listen(PORT, '127.0.0.1', async () => {
  log('server on 127.0.0.1:' + PORT, 'auth=', AUTH_DIR)
  loadSettings(); loadMessages(); await loadAuth()
  startSocket().catch(e => log('initial start err', e?.message))
})

process.on('uncaughtException', e => log('uncaughtException', e?.message))
process.on('unhandledRejection', e => log('unhandledRejection', e?.message))
