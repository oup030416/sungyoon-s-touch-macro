/** Anonymous, send-only feedback web app. Never log request bodies or retain their content. */
const FEEDBACK_LIMITS = Object.freeze({ text: 10000, bytes: 65536, minute: 10, day: 100,
  history: 3000, retentionMs: 30 * 24 * 60 * 60 * 1000 });

function doGet() {
  return feedbackReply_('ready', '');
}

function doPost(event) {
  let message;
  try {
    const raw = event && event.postData && event.postData.contents;
    if (typeof raw !== 'string' || raw.length > FEEDBACK_LIMITS.bytes ||
        Utilities.newBlob(raw).getBytes().length > FEEDBACK_LIMITS.bytes) return feedbackReply_('invalid', '');
    message = JSON.parse(raw);
    if (!validFeedback_(message)) return feedbackReply_('invalid', '');
  } catch (_) {
    return feedbackReply_('invalid', '');
  }

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(5000)) return feedbackReply_('retry', message.id);
  try {
    const properties = PropertiesService.getScriptProperties();
    const recipient = properties.getProperty('FEEDBACK_RECIPIENT');
    if (!recipient || !/^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]+$/.test(recipient)) return feedbackReply_('retry', message.id);
    const now = Date.now();
    const fingerprint = feedbackHash_(message);
    const key = 'message.' + message.id;
    const previous = properties.getProperty(key);
    if (previous) {
      const parts = previous.split('|');
      if (parts[0] !== fingerprint) return feedbackReply_('invalid', message.id);
      if (parts[1] === 'sent') return feedbackReply_('already_sent', message.id);
    }
    pruneFeedbackHistory_(properties, now);
    if (MailApp.getRemainingDailyQuota() < 1) return feedbackReply_('retry', message.id);
    const minute = String(Math.floor(now / 60000));
    const day = Utilities.formatDate(new Date(now), 'Asia/Seoul', 'yyyy-MM-dd');
    const minuteCount = feedbackCounter_(properties.getProperty('limit.minute'), minute);
    const dayCount = feedbackCounter_(properties.getProperty('limit.day'), day);
    if (minuteCount >= FEEDBACK_LIMITS.minute || dayCount >= FEEDBACK_LIMITS.day) return feedbackReply_('retry', message.id);

    // Reserve an attempt before sending, including ambiguous failures. Do not mark it sent early.
    properties.setProperties({
      'limit.minute': minute + '|' + (minuteCount + 1),
      'limit.day': day + '|' + (dayCount + 1),
      [key]: fingerprint + '|processing|' + now
    });
    MailApp.sendEmail({
      to: recipient,
      subject: '[성윤 터치매크로 피드백] ' + message.id,
      body: 'ID: ' + message.id + '\n앱 버전: ' + message.appVersion + ' (' + message.versionCode + ')' +
        '\n작성 시각: ' + new Date(message.createdAt).toISOString() + '\n\n' + message.text
    });
    properties.setProperty(key, fingerprint + '|sent|' + now);
    return feedbackReply_('sent', message.id);
  } catch (_) {
    // Mail may have been sent before a property write failed; retry favors no silent loss.
    return feedbackReply_('retry', message.id);
  } finally {
    lock.releaseLock();
  }
}

function validFeedback_(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  if (Object.keys(value).some(key => !['id', 'text', 'appVersion', 'versionCode', 'createdAt'].includes(key))) return false;
  return typeof value.id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value.id) &&
    typeof value.text === 'string' && value.text.trim().length > 0 && value.text.length <= FEEDBACK_LIMITS.text &&
    typeof value.appVersion === 'string' && /^[0-9A-Za-z._+-]{1,40}$/.test(value.appVersion) &&
    Number.isSafeInteger(value.versionCode) && value.versionCode > 0 && value.versionCode <= 2147483647 &&
    Number.isSafeInteger(value.createdAt) && value.createdAt > 0 && value.createdAt <= 8640000000000000;
}

function feedbackHash_(message) {
  const canonical = JSON.stringify([message.id, message.text, message.appVersion, message.versionCode, message.createdAt]);
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, canonical, Utilities.Charset.UTF_8)
    .map(value => (value & 255).toString(16).padStart(2, '0')).join('');
}

function feedbackCounter_(raw, bucket) {
  if (!raw) return 0;
  const parts = raw.split('|');
  return parts[0] === bucket ? (Number(parts[1]) || 0) : 0;
}

function pruneFeedbackHistory_(properties, now) {
  const entries = Object.entries(properties.getProperties()).filter(([key]) => key.startsWith('message.'))
    .map(([key, value]) => ({ key, time: Number(value.split('|')[2]) || 0 }))
    .sort((a, b) => b.time - a.time);
  entries.forEach((entry, index) => {
    // Leave one slot for the incoming record so the property store stays bounded.
    if (entry.time < now - FEEDBACK_LIMITS.retentionMs || index >= FEEDBACK_LIMITS.history - 1) properties.deleteProperty(entry.key);
  });
}

function feedbackReply_(status, id) {
  return ContentService.createTextOutput(JSON.stringify({ status, id })).setMimeType(ContentService.MimeType.JSON);
}

/** Run once from the editor to authorize the send-only scope without sending a test email. */
function authorizeFeedback() {
  if (!PropertiesService.getScriptProperties().getProperty('FEEDBACK_RECIPIENT')) throw new Error('Configure FEEDBACK_RECIPIENT first');
  MailApp.getRemainingDailyQuota();
}
