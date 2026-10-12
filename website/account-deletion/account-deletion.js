export const APPROVED_ORIGIN = "https://linkfilebox.com";
export const API_BASE =
  "https://api.linkfilebox.com/functions/v1/library-api/v1/web-account";
export const GOOGLE_CLIENT_ID =
  "486032328491-4sv6bg4ukablou6d2btfnhon30e10rsb.apps.googleusercontent.com";
export const GIS_URL = "https://accounts.google.com/gsi/client";
export const RETRY_WINDOW_MS = 5 * 60 * 1000;
export const MAX_MANUAL_RETRIES = 2;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NONCE = /^[A-Za-z0-9_-]{43}$/;
const TIMESTAMP =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/;
const MESSAGES = {
  idle:
    "삭제 요청 또는 진행 상태 조회를 선택하세요. Google 인증은 선택한 절차에서만 시작됩니다.",
  disabled:
    "웹 기능이 활성화되지 않았거나 공개 설정을 확인할 수 없습니다. 앱 내 절차를 이용하세요.",
  origin: "웹 기능은 https://linkfilebox.com에서만 사용할 수 있습니다.",
  expired:
    "인증 유효 시간이 지났습니다. 인증 정보를 지웠습니다. 새 Google 인증으로 다시 진행하세요.",
  malformed:
    "서버 응답을 확인할 수 없습니다. 삭제 완료로 판단하지 마세요. 새 Google 인증으로 진행 상태를 조회하세요.",
  ambiguous:
    "전송 결과를 확인할 수 없습니다. 요청이 접수되었을 수도 있으며 삭제 완료가 아닙니다. 제한 시간 안에 동일 요청을 다시 전송하거나 새 Google 인증으로 진행 상태를 조회하세요.",
};

function object(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
function exact(value, keys) {
  return object(value) && Object.keys(value).length === keys.length &&
    keys.every((key) => Object.hasOwn(value, key));
}
function timestamp(value) {
  return typeof value === "string" && TIMESTAMP.test(value) &&
    Number.isFinite(Date.parse(value));
}
function publicKey(value) {
  if (typeof value !== "string" || value.length > 4096) return false;
  if (/^sb_publishable_[A-Za-z0-9_-]+$/.test(value)) return true;
  if (!/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value)) {
    return false;
  }
  try {
    const part = value.split(".")[1].replaceAll("-", "+").replaceAll("_", "/");
    // Configuration safety only, not authentication or JWT signature verification.
    return JSON.parse(atob(part.padEnd(Math.ceil(part.length / 4) * 4, "=")))
      .role === "anon";
  } catch {
    return false;
  }
}
export function validConfig(value) {
  return exact(value, [
    "apiBase",
    "googleClientId",
    "publishableKey",
    "enabled",
  ]) &&
    value.enabled === true && value.apiBase === API_BASE &&
    value.googleClientId === GOOGLE_CLIENT_ID &&
    publicKey(value.publishableKey);
}
function validChallenge(value, purpose, now) {
  return exact(value, ["challenge_id", "nonce", "expires_at", "purpose"]) &&
    typeof value.challenge_id === "string" && UUID.test(value.challenge_id) &&
    typeof value.nonce === "string" && NONCE.test(value.nonce) &&
    value.purpose === purpose &&
    timestamp(value.expires_at) && Date.parse(value.expires_at) > now &&
    Date.parse(value.expires_at) <= now + RETRY_WINDOW_MS + 30000;
}
function validStatus(value, now) {
  if (
    !exact(value, [
      "state",
      "requested_at",
      "completed_at",
      "status_available_until",
    ])
  ) return false;
  if (value.state === "not_requested") {
    return value.requested_at === null && value.completed_at === null &&
      value.status_available_until === null;
  }
  if (!timestamp(value.requested_at)) return false;
  if (value.state === "deleting") {
    return value.completed_at === null && value.status_available_until === null;
  }
  return value.state === "deleted" && timestamp(value.completed_at) &&
    timestamp(value.status_available_until) &&
    Date.parse(value.completed_at) >= Date.parse(value.requested_at) &&
    Date.parse(value.status_available_until) > Date.parse(value.completed_at) &&
    Date.parse(value.status_available_until) > now;
}
function failureMessage(status, purpose) {
  if (status === 401) {
    return "Google 인증을 확인할 수 없습니다. 삭제 완료가 아닙니다. 새 Google 인증을 진행하세요.";
  }
  if (status === 404) {
    return purpose === "status"
      ? "보관된 진행 상태를 찾을 수 없습니다. 조회 보관 기간이 끝났거나 연결된 계정을 찾지 못할 수 있습니다. 삭제 완료를 뜻하지 않습니다."
      : "이 Google 계정에 연결된 기존 회원을 찾을 수 없습니다. 신규 가입은 하지 않으며 삭제 완료를 뜻하지 않습니다.";
  }
  if (status === 403 || status === 409) {
    return "요청을 처리할 수 없습니다. 이미 처리 중이거나 인증이 만료되었을 수 있습니다. 삭제 완료로 판단하지 말고 새 Google 인증으로 진행 상태를 조회하세요.";
  }
  if (status === 429) {
    return "요청이 많아 잠시 사용할 수 없습니다. 삭제 완료가 아닙니다. 잠시 기다린 후 다시 확인하세요.";
  }
  return "요청을 처리하지 못했습니다. 삭제 완료가 아닙니다. 새 Google 인증으로 진행 상태를 조회하세요.";
}

// Proofs and exact replay bodies stay in this closure, never in observable UI state.
export function createAccountDeletionController({
  fetch: fetchRequest,
  clock = Date.now,
  uuid = () => crypto.randomUUID(),
  origin,
  onChange = () => {},
  timeoutMs = 15000,
}) {
  let config = null;
  let generation = 0;
  let challenge = null;
  let token = null;
  let pending = null;
  let activeAbort = null;
  let initialized = null;
  let deleteWasSent = false;
  const usedRequestIds = new Set();
  const state = {
    phase: "disabled",
    purpose: null,
    consent: false,
    generation,
    expiresAt: null,
    message: MESSAGES.disabled,
    error: false,
    result: null,
    canRetry: false,
  };
  function snapshot() {
    return { ...state, result: state.result ? { ...state.result } : null };
  }
  function update(values) {
    Object.assign(state, values, { generation });
    onChange(snapshot());
  }
  function wipe() {
    challenge = null;
    token = null;
    pending = null;
    activeAbort?.abort();
    activeAbort = null;
  }
  function finishError(message) {
    wipe();
    update({
      phase: "error",
      error: true,
      message,
      expiresAt: null,
      canRetry: false,
      result: null,
    });
  }
  function nextId() {
    const value = uuid();
    if (typeof value !== "string" || !UUID.test(value)) {
      throw new Error("Invalid request ID.");
    }
    if (usedRequestIds.has(value.toLowerCase())) {
      throw new Error("Request IDs must be fresh.");
    }
    usedRequestIds.add(value.toLowerCase());
    return value;
  }
  async function request(url, init) {
    const abort = new AbortController();
    activeAbort = abort;
    let timer;
    try {
      return await Promise.race([
        (async () => {
          const response = await fetchRequest(url, {
            ...init,
            signal: abort.signal,
          });
          const data = await response.json();
          return { status: response.status, data };
        })(),
        new Promise((_, reject) => {
          timer = setTimeout(() => {
            abort.abort();
            reject(new Error("Response unavailable."));
          }, timeoutMs);
        }),
      ]);
    } finally {
      clearTimeout(timer);
      if (activeAbort === abort) activeAbort = null;
    }
  }
  function post(action, requestId, body) {
    if (origin !== APPROVED_ORIGIN || !config) {
      throw new Error("Web requests disabled.");
    }
    return request(`${config.apiBase}/${action}`, {
      method: "POST",
      credentials: "omit",
      cache: "no-store",
      redirect: "error",
      referrerPolicy: "no-referrer",
      headers: {
        "Content-Type": "application/json",
        "X-Request-Id": requestId,
        apikey: config.publishableKey,
      },
      body,
    });
  }
  function expire() {
    if (
      (state.phase === "authenticating" || state.phase === "proof") &&
      challenge && clock() >= Date.parse(challenge.expires_at)
    ) {
      generation++;
      finishError(MESSAGES.expired);
    } else if (
      state.phase === "ambiguous" && pending && clock() >= pending.retryUntil
    ) {
      generation++;
      finishError(
        "동일 요청 재전송 시간이 지났습니다. 인증 정보를 지웠습니다. 삭제 완료 여부는 새 Google 인증으로 진행 상태를 조회하세요.",
      );
    }
    return snapshot();
  }
  function initialize() {
    if (initialized) return initialized;
    initialized = (async () => {
      if (origin !== APPROVED_ORIGIN) {
        update({ message: MESSAGES.origin, error: true });
        return false;
      }
      update({
        phase: "loading",
        message: "공개 웹 설정을 확인하고 있습니다.",
      });
      try {
        const reply = await request("/account-deletion/config.json", {
          method: "GET",
          credentials: "omit",
          cache: "no-store",
          redirect: "error",
          referrerPolicy: "no-referrer",
        });
        if (reply.status !== 200 || !validConfig(reply.data)) {
          throw new Error("Invalid public configuration.");
        }
        config = { ...reply.data };
        update({ phase: "idle", message: MESSAGES.idle, error: false });
        return true;
      } catch {
        config = null;
        update({ phase: "disabled", message: MESSAGES.disabled, error: true });
        return false;
      }
    })();
    return initialized;
  }
  async function start(purpose) {
    if (
      !config || !["delete", "status"].includes(purpose) ||
      !["idle", "error", "result"].includes(state.phase) ||
      (purpose === "delete" && !state.consent)
    ) return false;
    wipe();
    deleteWasSent = false;
    const current = ++generation;
    update({
      phase: "challenging",
      purpose,
      result: null,
      expiresAt: null,
      canRetry: false,
      error: false,
      message: "본인 확인 요청을 준비하고 있습니다.",
    });
    try {
      const id = nextId();
      const reply = await post("challenge", id, JSON.stringify({ purpose }));
      if (current !== generation) return false;
      if (reply.status !== 201) {
        finishError(failureMessage(reply.status, purpose));
        return false;
      }
      if (!validChallenge(reply.data, purpose, clock())) {
        finishError(MESSAGES.malformed);
        return false;
      }
      challenge = { ...reply.data };
      update({
        phase: "authenticating",
        expiresAt: challenge.expires_at,
        message: purpose === "delete"
          ? "삭제 요청용 Google 버튼을 눌러 삭제할 기존 Google 계정을 직접 선택하세요. 아직 삭제 요청을 보내지 않았습니다."
          : "진행 상태 조회용 Google 버튼을 눌러 기존 Google 계정을 직접 선택하세요. 계정을 삭제하거나 가입하지 않습니다.",
      });
      return true;
    } catch {
      if (current === generation) {
        finishError(
          "본인 확인 요청을 준비하지 못했습니다. 삭제 요청은 보내지 않았습니다. 다시 시작하세요.",
        );
      }
      return false;
    }
  }
  function acceptProof(callbackGeneration, credential) {
    expire();
    if (
      callbackGeneration !== generation || state.phase !== "authenticating" ||
      !challenge
    ) return false;
    if (
      typeof credential !== "string" ||
      !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(credential) ||
      credential.length > 16384
    ) {
      finishError(
        "Google 인증 응답을 확인할 수 없습니다. 새 Google 인증을 진행하세요.",
      );
      return false;
    }
    token = credential;
    update({
      phase: "proof",
      message: state.purpose === "delete"
        ? "Google 인증 정보를 받았습니다. 삭제는 아직 요청하지 않았습니다. 동의를 확인한 뒤 ‘최종 삭제 요청 보내기’를 눌러 서버에 전송하세요."
        : "Google 인증 정보를 받았습니다. 진행 상태를 확인합니다.",
    });
    return true;
  }
  async function transmit(current) {
    const sent = pending;
    if (sent.purpose === "delete") deleteWasSent = true;
    update({
      phase: "sending",
      error: false,
      canRetry: false,
      message: state.purpose === "delete"
        ? "삭제 요청을 전송하고 있습니다. 완료를 뜻하지 않습니다."
        : "인증된 진행 상태를 조회하고 있습니다.",
    });
    try {
      const reply = await post(sent.purpose, sent.id, sent.body);
      if (current !== generation || sent !== pending) return false;
      const valid = sent.purpose === "delete"
        ? reply.status === 202 && exact(reply.data, ["state"]) &&
          reply.data.state === "deleting"
        : reply.status === 200 && validStatus(reply.data, clock());
      if (valid) {
        const result = { ...reply.data };
        wipe();
        update({
          phase: "result",
          result,
          expiresAt: null,
          consent: false,
          message: result.state === "deleting"
            ? "삭제 요청이 접수되어 처리 중입니다. 아직 삭제 완료가 아닙니다. 새 Google 인증으로 진행 상태를 다시 조회할 수 있습니다."
            : result.state === "deleted"
            ? "인증된 상태 조회에서 삭제 완료를 확인했습니다. 백업 및 기기 자료 제한은 위의 안내를 확인하세요."
            : "이 계정의 삭제 요청이 확인되지 않았습니다. 계정은 삭제되지 않았습니다.",
        });
        return true;
      }
      if (
        reply.status >= 500 || reply.status === 429 ||
        (reply.status >= 200 && reply.status < 300)
      ) {
        ambiguous();
      } else {
        finishError(failureMessage(reply.status, sent.purpose));
      }
    } catch {
      if (current === generation && sent === pending) ambiguous();
    }
    return false;
  }
  function ambiguous() {
    token = null;
    challenge = null;
    const canRetry = pending && pending.retries < MAX_MANUAL_RETRIES &&
      clock() < pending.retryUntil;
    if (!canRetry) {
      finishError(
        `${MESSAGES.ambiguous} 동일 요청 재전송 한도가 끝나 인증 정보를 지웠습니다.`,
      );
      return;
    }
    update({
      phase: "ambiguous",
      expiresAt: null,
      error: true,
      canRetry: true,
      result: null,
      message: MESSAGES.ambiguous,
    });
  }
  async function send() {
    expire();
    if (
      state.phase !== "proof" || !challenge || !token ||
      (state.purpose === "delete" && !state.consent)
    ) return false;
    try {
      const id = nextId();
      pending = {
        id,
        purpose: state.purpose,
        body: JSON.stringify({
          challenge_id: challenge.challenge_id,
          google_id_token: token,
        }),
        retries: 0,
        retryUntil: clock() + RETRY_WINDOW_MS,
      };
      token = null;
      return await transmit(generation);
    } catch {
      finishError(
        "전송 요청을 준비하지 못했습니다. 새 Google 인증을 진행하세요.",
      );
      return false;
    }
  }
  async function retry() {
    expire();
    if (
      state.phase !== "ambiguous" || !pending || !state.canRetry ||
      (pending.purpose === "delete" && !state.consent)
    ) return false;
    pending.retries++;
    return transmit(generation);
  }
  function cancel() {
    if (["disabled", "loading"].includes(state.phase)) return;
    const sent = deleteWasSent ||
      ["sending", "ambiguous"].includes(state.phase);
    generation++;
    wipe();
    deleteWasSent = false;
    update({
      phase: "idle",
      purpose: null,
      consent: false,
      expiresAt: null,
      result: null,
      canRetry: false,
      error: false,
      message: sent
        ? "인증 정보를 지웠습니다. 이미 전송한 요청은 철회되지 않습니다. 새 Google 인증으로 진행 상태를 조회하세요."
        : "취소하고 인증 정보를 지웠습니다. 삭제 요청을 보내지 않았습니다.",
    });
  }
  function setConsent(value) {
    if (
      value !== true && state.purpose === "delete" &&
      ["challenging", "authenticating", "proof", "sending", "ambiguous"]
        .includes(state.phase)
    ) cancel();
    update({ consent: value === true });
  }
  function googleOptions(callback) {
    expire();
    if (state.phase !== "authenticating" || !challenge) return null;
    const current = generation;
    return {
      client_id: GOOGLE_CLIENT_ID,
      nonce: challenge.nonce,
      auto_select: false,
      ux_mode: "popup",
      context: "use",
      use_fedcm_for_prompt: false,
      use_fedcm_for_button: false,
      button_auto_select: false,
      callback: (response) => {
        if (acceptProof(current, response?.credential)) callback(snapshot());
      },
    };
  }
  function authenticationFailed(current) {
    if (current === generation && state.phase === "authenticating") {
      finishError(
        "Google 인증 버튼을 불러오지 못했습니다. 인증 정보를 지웠습니다. 다시 시작하세요.",
      );
    }
  }
  return {
    initialize,
    start,
    acceptProof,
    send,
    retry,
    cancel,
    setConsent,
    expire,
    googleOptions,
    authenticationFailed,
    getState: snapshot,
  };
}

let gisLoading;
function loadGoogleButton() {
  if (globalThis.google?.accounts?.id) {
    return Promise.resolve(globalThis.google.accounts.id);
  }
  if (gisLoading) return gisLoading;
  gisLoading = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = GIS_URL;
    script.async = true;
    script.referrerPolicy = "no-referrer";
    let timer;
    function finish(error) {
      clearTimeout(timer);
      script.onload = null;
      script.onerror = null;
      if (error) {
        script.remove();
        gisLoading = null;
        reject(new Error("Google button unavailable."));
      } else resolve(globalThis.google.accounts.id);
    }
    script.onload = () => finish(!globalThis.google?.accounts?.id);
    script.onerror = () => finish(true);
    timer = setTimeout(() => finish(true), 15000);
    document.head.append(script);
  });
  return gisLoading;
}

function mountAccountDeletion() {
  const root = document.getElementById("web-account");
  if (!root) return;
  const element = (id) => document.getElementById(`account-${id}`);
  const consent = element("consent");
  const startDelete = element("start-delete");
  const startStatus = element("start-status");
  const send = element("send");
  const retry = element("retry");
  const cancel = element("cancel");
  const area = element("google-area");
  const button = element("google-button");
  let googleGeneration = null;
  const controller = createAccountDeletionController({
    fetch: globalThis.fetch.bind(globalThis),
    origin: globalThis.location.origin,
    onChange: (state) => {
      const available = ["idle", "error", "result"].includes(state.phase);
      root.setAttribute(
        "aria-busy",
        String(["loading", "challenging", "sending"].includes(state.phase)),
      );
      startDelete.disabled = !available || !state.consent;
      startStatus.disabled = !available;
      consent.checked = state.consent;
      consent.disabled = state.phase === "loading" ||
        state.phase === "disabled";
      send.hidden = state.phase !== "proof" || state.purpose !== "delete";
      send.disabled = send.hidden || !state.consent;
      retry.hidden = state.phase !== "ambiguous";
      retry.disabled = !state.canRetry ||
        (state.purpose === "delete" && !state.consent);
      cancel.hidden = ![
        "challenging",
        "authenticating",
        "proof",
        "sending",
        "ambiguous",
      ].includes(state.phase);
      area.hidden = state.phase !== "authenticating";
      if (area.hidden) {
        button.replaceChildren();
        googleGeneration = null;
      }
      const message = element("message");
      message.textContent = state.message;
      message.dataset.error = String(state.error);
      message.setAttribute("role", state.error ? "alert" : "status");
      message.setAttribute("aria-live", state.error ? "assertive" : "polite");
      element("result").hidden = !state.result;
      element("result-times").replaceChildren();
      if (state.result) {
        element("result-state").textContent = {
          not_requested: "삭제 요청 없음",
          deleting: "삭제 처리 중 (완료 아님)",
          deleted: "삭제 완료",
        }[state.result.state];
        for (
          const [key, label] of [["requested_at", "요청 접수 시각"], [
            "completed_at",
            "삭제 완료 시각",
          ], ["status_available_until", "이 상태의 조회 가능 기한"]]
        ) {
          if (!state.result[key]) continue;
          const term = document.createElement("dt");
          term.textContent = label;
          const description = document.createElement("dd");
          const time = document.createElement("time");
          time.dateTime = state.result[key];
          time.textContent =
            new Date(state.result[key]).toLocaleString("ko-KR", {
              timeZone: "Asia/Seoul",
            }) + " (한국 시간)";
          description.append(time);
          element("result-times").append(term, description);
        }
      }
    },
  });
  async function start(purpose) {
    if (!await controller.start(purpose)) return;
    const current = controller.getState().generation;
    googleGeneration = current;
    element("google-purpose").textContent = purpose === "delete"
      ? "삭제 요청을 위한 Google 본인 확인 (인증 후 최종 전송 필요)"
      : "진행 상태 조회를 위한 Google 본인 확인 (삭제 요청 아님)";
    try {
      const googleId = await loadGoogleButton();
      const state = controller.expire();
      if (
        state.generation !== current || state.phase !== "authenticating" ||
        googleGeneration !== current
      ) return;
      const options = controller.googleOptions((proofState) => {
        if (proofState.purpose === "status") void controller.send();
        else send.focus();
      });
      if (!options) return;
      googleId.initialize(options);
      googleId.renderButton(button, {
        type: "standard",
        theme: "outline",
        size: "large",
        text: "continue_with",
        locale: "ko",
      });
    } catch {
      controller.authenticationFailed(current);
    }
  }
  consent.addEventListener(
    "change",
    () => controller.setConsent(consent.checked),
  );
  startDelete.addEventListener("click", () => {
    void start("delete");
  });
  startStatus.addEventListener("click", () => {
    void start("status");
  });
  send.addEventListener("click", () => {
    void controller.send();
  });
  retry.addEventListener("click", () => {
    void controller.retry();
  });
  cancel.addEventListener("click", () => {
    controller.cancel();
    startStatus.focus();
    globalThis.google?.accounts?.id?.cancel();
  });
  const expiryTimer = setInterval(() => controller.expire(), 1000);
  globalThis.addEventListener("pagehide", () => {
    clearInterval(expiryTimer);
    controller.cancel();
    globalThis.google?.accounts?.id?.cancel();
  });
  globalThis.addEventListener("pageshow", (event) => {
    if (event.persisted) globalThis.location.reload();
  });
  void controller.initialize();
}
if (typeof document !== "undefined") mountAccountDeletion();
