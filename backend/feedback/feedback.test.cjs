const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { join } = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');

function backend() {
  const values = { FEEDBACK_RECIPIENT: 'owner@example.com' };
  const state = { now: 1791500000000, quota: 100, locked: false, sent: [], failSend: false, failAck: false };
  const properties = {
    getProperty: key => values[key] ?? null,
    getProperties: () => ({ ...values }),
    setProperty: (key, value) => { if (state.failAck && value.includes('|sent|')) throw Error('Ack unavailable'); values[key] = value; },
    setProperties: entries => Object.assign(values, entries),
    deleteProperty: key => delete values[key]
  };
  const context = vm.createContext({
    Date: class extends Date { static now() { return state.now; } },
    PropertiesService: { getScriptProperties: () => properties },
    LockService: { getScriptLock: () => ({ tryLock: () => !state.locked, releaseLock() {} }) },
    MailApp: { getRemainingDailyQuota: () => state.quota, sendEmail: email => {
      if (state.failSend) throw Error('Mail unavailable');
      state.sent.push(email); state.quota--;
    } },
    Utilities: { DigestAlgorithm: { SHA_256: 'sha256' }, Charset: { UTF_8: 'utf8' },
      newBlob: raw => ({ getBytes: () => Buffer.from(raw, 'utf8') }),
      computeDigest: (_, raw) => Array.from(crypto.createHash('sha256').update(raw).digest()),
      formatDate: date => date.toLocaleDateString('en-CA', { timeZone: 'Asia/Seoul' }) },
    ContentService: { MimeType: { JSON: 'json' }, createTextOutput: body => ({ setMimeType: () => JSON.parse(body) }) }
  });
  vm.runInContext(readFileSync(join(__dirname, 'Code.gs'), 'utf8'), context);
  const message = (text = '한글 요청\n😀') => ({ id: crypto.randomUUID(), text, appVersion: '1.3', versionCode: 23, createdAt: state.now });
  const post = value => context.doPost({ postData: { contents: JSON.stringify(value) } });
  return { context, state, values, message, post };
}

test('plain-text full feedback goes only to the server recipient and content is not stored', () => {
  const app = backend(); const message = app.message('<script>hello</script>\n한글😀');
  assert.equal(app.post(message).status, 'sent');
  const mail = app.state.sent[0];
  assert.equal(mail.to, 'owner@example.com');
  assert.ok(mail.body.endsWith(message.text));
  assert.equal(mail.htmlBody, undefined);
  assert.ok(mail.subject.endsWith(message.id));
  assert.ok(!JSON.stringify(app.values).includes(message.text));
});

test('lost responses are idempotent and same-ID changed content is rejected', () => {
  const app = backend(); const message = app.message();
  assert.equal(app.post(message).status, 'sent');
  assert.equal(app.post(message).status, 'already_sent');
  assert.equal(app.post({ ...message, text: 'changed' }).status, 'invalid');
  assert.equal(app.state.sent.length, 1);
});

test('rejects blank, oversized, invalid and recipient override payloads', () => {
  const app = backend();
  for (const message of [app.message(' \n\t'), app.message('가'.repeat(10001)), { ...app.message(), to: 'other@example.com' },
    { ...app.message(), appVersion: '1.3\nInjected' }, { ...app.message(), id: 'not-uuid' }, { ...app.message(), createdAt: 1e30 }]) {
    assert.equal(app.post(message).status, 'invalid');
  }
  assert.equal(app.context.doPost({ postData: { contents: '{invalid' } }).status, 'invalid');
  assert.equal(app.context.doPost({ postData: { contents: ' '.repeat(65537) } }).status, 'invalid');
  assert.equal(app.post(app.message('가'.repeat(10000))).status, 'sent');
  assert.equal(app.state.sent.length, 1);
});

test('lock contention, unavailable quota and send failures return retry without false ack', () => {
  const app = backend(); const message = app.message();
  app.state.locked = true; assert.equal(app.post(message).status, 'retry');
  app.state.locked = false; app.state.quota = 0; assert.equal(app.post(message).status, 'retry');
  app.state.quota = 100; app.state.failSend = true; assert.equal(app.post(message).status, 'retry');
  assert.ok(app.values['message.' + message.id].includes('|processing|'));
  app.state.failSend = false; assert.equal(app.post(message).status, 'sent');
  assert.equal(app.state.sent.length, 1);
});

test('minute/day limits persist and quota remains authoritative', () => {
  const app = backend();
  for (let i = 0; i < 10; i++) assert.equal(app.post(app.message()).status, 'sent');
  assert.equal(app.post(app.message()).status, 'retry');
  app.state.now += 61000;
  assert.equal(app.post(app.message()).status, 'sent');
  for (let i = 11; i < 100; i++) {
    app.state.now += 61000;
    assert.equal(app.post(app.message()).status, 'sent');
  }
  app.state.quota = 100;
  app.state.now += 61000;
  assert.equal(app.post(app.message()).status, 'retry');
  app.state.now += 86400000;
  assert.equal(app.post(app.message()).status, 'sent');
});

test('post-mail acknowledgement failures favor retry and duplicates remain identifiable', () => {
  const app = backend(); const message = app.message();
  app.state.failAck = true;
  assert.equal(app.post(message).status, 'retry');
  app.state.failAck = false;
  assert.equal(app.post(message).status, 'sent');
  assert.equal(app.state.sent.length, 2);
  assert.equal(app.state.sent[0].subject, app.state.sent[1].subject);
});

test('history is bounded and old metadata is pruned without deleting configuration', () => {
  const app = backend();
  for (let i = 0; i < 3000; i++) app.values['message.' + crypto.randomUUID()] = 'hash|sent|' + (app.state.now - i);
  const old = 'message.' + crypto.randomUUID();
  app.values[old] = 'hash|sent|' + (app.state.now - 31 * 86400000);
  assert.equal(app.post(app.message()).status, 'sent');
  assert.equal(Object.keys(app.values).filter(key => key.startsWith('message.')).length, 3000);
  assert.equal(app.values[old], undefined);
  assert.equal(app.values.FEEDBACK_RECIPIENT, 'owner@example.com');
});

test('authorization and health check never send emails or disclose the owner', () => {
  const app = backend();
  app.context.authorizeFeedback();
  assert.deepEqual(app.context.doGet(), { status: 'ready', id: '' });
  assert.equal(app.state.sent.length, 0);
});
