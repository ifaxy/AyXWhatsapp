// WA Gateway - Node/Baileys engine (Phase 1.1)
// Link, send, always-online, auto-read, auto-reply, anti-delete (all msgs), message log.

const fs = require('fs')
const path = require('path')
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
  aiModel: 'llama-3.3-70b-versatile',
  aiSystemPrompt: '',
  saveMedia: false,
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
const presences = new Map()    // jid -> { presence, lastSeen }
let statuses = []              // status@broadcast items (newest first)

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
  return msg.pushName || msg.verifiedBizName || (c && c.name) || ''
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
        model: settings.aiModel || 'llama-3.3-70b-versatile',
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
          const sEntry = { sender: sndr, name: fromMe ? 'My Status' : (msg.pushName || ''), mine: fromMe, text: extractText(msg.message), ts: sTs }
          try { await enrichMedia(msg, sEntry) } catch (_) {}
          statuses.unshift(sEntry)
          if (statuses.length > 120) statuses.length = 120
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
  if (cts) for (const c of cts) { if (c.id) contacts.set(c.id, { name: c.name || c.notify || '', notify: c.notify || '' }) }
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
          const sE = { sender: sndr2, name: msg.key.fromMe ? 'My Status' : (msg.pushName || ''), mine: !!msg.key.fromMe, text: extractText(msg.message), ts: sTs2 }
          try { await enrichMedia(msg, sE) } catch (_) {}
          if (!statuses.find(x => x.sender === sE.sender && x.ts === sE.ts)) { statuses.unshift(sE); if (statuses.length > 120) statuses.length = 120 }
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
  const addContacts = (list) => { for (const c of list || []) { if (c.id) contacts.set(c.id, { name: c.name || c.notify || '', notify: c.notify || '' }) } }
  sock.ev.on('contacts.upsert', addContacts)
  sock.ev.on('contacts.set', ({ contacts: cs }) => addContacts(cs))
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
  for (const [jid, c] of contacts) {
    if (!jid.endsWith('@s.whatsapp.net')) continue
    items.push({ jid, name: c.name || c.notify || '', number: jid.split('@')[0] })
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

function parseMusicSearch(data) {
  const items = []
  try {
    const tabs = data?.contents?.tabbedSearchResultsRenderer?.tabs || []
    for (const tab of tabs) {
      const sections = tab?.tabRenderer?.content?.sectionListRenderer?.contents || []
      for (const sec of sections) {
        const shelf = sec?.musicShelfRenderer
        if (!shelf) continue
        for (const it of (shelf.contents || [])) {
          const r = it?.musicResponsiveListItemRenderer
          if (!r) continue
          const flex = r.flexColumns || []
          const title = flex[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text
          const subRuns = flex[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || []
          const artist = subRuns.map(x => x.text).join('')
          const vid = r.playlistItemData?.videoId
            || r.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId
          const thumbs = r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails || []
          const thumb = thumbs[thumbs.length - 1]?.url || null
          if (title && vid) items.push({ title, artist, videoId: vid, thumb })
        }
      }
    }
  } catch (_) {}
  return items
}

let ytdl = null
try { ytdl = require('@distube/ytdl-core') } catch (e) { }

app.get('/music/stream', async (req, res) => {
  try {
    if (!ytdl) return res.status(500).json({ error: 'stream engine unavailable' })
    const videoId = String(req.query.videoId || '')
    if (!videoId) return res.status(400).json({ error: 'videoId required' })
    const info = await ytdl.getInfo('https://www.youtube.com/watch?v=' + videoId)
    const fmt = ytdl.chooseFormat(info.formats, { quality: 'highestaudio', filter: 'audioonly' })
    res.json({ url: fmt.url, duration: Number(info.videoDetails.lengthSeconds || 0) })
  } catch (e) { log('music stream err', e?.message); res.status(500).json({ error: e?.message }) }
})

app.get('/music/search', async (req, res) => {
  try {
    const q = String(req.query.q || '').trim()
    if (!q) return res.json({ items: [] })
    const body = {
      context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240403.01.00', hl: 'en', gl: 'US' } },
      query: q,
      params: 'EgWKAQIIAWoKEAoQAxAEEAkQBQ%3D%3D'
    }
    const r = await fetch('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'Mozilla/5.0', 'Origin': 'https://music.youtube.com' },
      body: JSON.stringify(body)
    })
    const data = await r.json()
    res.json({ items: parseMusicSearch(data).slice(0, 25) })
  } catch (e) { log('music search err', e?.message); res.json({ items: [] }) }
})

app.post('/status/post', async (req, res) => {
  try {
    if (!sock || status.connection !== 'open') return res.status(409).json({ error: 'not connected' })
    const type = String(req.body?.type || 'image')
    const b64 = String(req.body?.data || '')
    const caption = String(req.body?.caption || '')
    if (!b64) return res.status(400).json({ error: 'data required' })
    const buf = Buffer.from(b64, 'base64')
    const content = type === 'video' ? { video: buf, caption } : { image: buf, caption }
    const jids = Array.from(contacts.keys()).filter(j => j.endsWith('@s.whatsapp.net'))
    await sock.sendMessage('status@broadcast', content, jids.length ? { statusJidList: jids } : {})
    res.json({ ok: true })
  } catch (e) { log('status post err', e?.message); res.status(500).json({ error: e?.message }) }
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
  if (typeof b.autoReplyEnabled === 'boolean') settings.autoReplyEnabled = b.autoReplyEnabled
  if (Array.isArray(b.autoReplyRules)) settings.autoReplyRules = b.autoReplyRules
  if (typeof b.aiReplyEnabled === 'boolean') settings.aiReplyEnabled = b.aiReplyEnabled
  if (typeof b.aiApiUrl === 'string') settings.aiApiUrl = b.aiApiUrl
  if (typeof b.aiApiKey === 'string') settings.aiApiKey = b.aiApiKey
  if (typeof b.aiModel === 'string') settings.aiModel = b.aiModel
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
      try { const r = await sock.onWhatsApp(...chunk); for (const x of (r || [])) if (x?.exists) out.push(String(x.jid).split('@')[0].split(':')[0]) } catch (_) {}
    }
    res.json({ items: out })
  } catch (e) { res.json({ items: [] }) }
})

app.get('/statuses', (req, res) => res.json({ items: statuses.slice(0, 120) }))
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
