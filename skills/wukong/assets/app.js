"use strict";
const $ = (id) => document.getElementById(id);
const words = {
  en: {
    brand: "Wukong",
    connecting: "Connecting…",
    title: "Your phone. One request away.",
    subtitle: "Delegate the goal. Wukong handles the steps on your phone.",
    goalLabel: "What would you like done?",
    goalPlaceholder:
      "Read the recipe open in Chrome, then prepare a timer in Clock. Don’t start it yet.",
    screen: "Screen",
    virtual: "Virtual display",
    physical: "Phone screen",
    run: "Run on phone",
    current: "Current task",
    takeover: "Take over",
    resume: "Resume agent",
    stop: "Stop",
    logs: "Export run log",
    modelConfig: "Execution model",
    modelNote:
      "The phone uses this model to execute tasks. Your calling agent only delegates the goal. API usage is billed by your provider.",
    provider: "Provider",
    platform: "Get an API key ↗",
    keyNote:
      "Sent only to the phone. Encrypted with Android Keystore, including standalone mode.",
    model: "Model name",
    fetchModels: "Fetch models",
    advanced: "Advanced",
    full: "URL already includes the complete chat endpoint",
    thinking: "Thinking mode",
    thinkingNote:
      "Off by default. Some models always reason; choose a model that supports disabling thinking for faster execution.",
    save: "Test & save to phone",
    live: "On your phone",
    empty: "The execution screen appears here.",
    previewNote:
      "Take over to interact with the video. The App also offers a floating window.",
    boundary:
      "Closing this page keeps the task running. Payments and destructive actions require your confirmation.",
    local: "Local connection · ADB authorized · No cloud relay",
    close: "Close companion",
    ready: "Connected",
    engine: "Activate the phone engine in Wukong first.",
    overlay: "Allow Wukong overlay controls on the phone first.",
    configure: "Configure an execution model first.",
    saved: "Tested and saved on phone.",
    testing: "Testing from phone…",
    loading: "Loading…",
    noModels:
      "This provider did not return models. Enter the model name manually.",
    configuredKey: "Saved key on phone · leave blank to keep",
    newKey: "Enter your API key",
    approval: "Confirm the sensitive action on your phone.",
    closed: "Companion closed. Tasks already submitted continue on the phone.",
    notConnected: "Disconnected",
    retry:
      "Connection interrupted. Querying the same task ID; do not submit a duplicate.",
    unsubmitted: "No receipt found yet. Retry with the same task ID.",
    manual: "Paused · interact with the video or your phone",
    step: "Step",
    unknown: "Saved model",
    phases: {
      RUNNING: "Running",
      PREPARING: "Preparing",
      PAUSED: "You are in control",
      AWAITING_APPROVAL: "Needs your confirmation",
      STOPPING: "Stopping",
      COMPLETED: "Completed",
      FAILED: "Failed",
      CANCELLED: "Stopped",
      INTERRUPTED: "Interrupted",
      IDLE: "Ready",
    },
  },
  zh: {
    brand: "悟空",
    connecting: "正在连接…",
    title: "一句话，把手机交给悟空。",
    subtitle: "你提出目标，悟空在手机上完成操作。",
    goalLabel: "想让悟空帮你做什么？",
    goalPlaceholder:
      "读取 Chrome 中打开的食谱，再去时钟按食谱时长准备倒计时，先不要开始。",
    screen: "执行位置",
    virtual: "虚拟屏",
    physical: "手机主屏",
    run: "在手机上执行",
    current: "当前任务",
    takeover: "接管",
    resume: "交还 Agent",
    stop: "停止",
    logs: "导出本次日志",
    modelConfig: "执行模型",
    modelNote:
      "手机使用这个模型执行任务，外部 Agent 只需提交目标。API 用量由模型服务商计费。",
    provider: "服务商",
    platform: "获取 API Key ↗",
    keyNote:
      "密钥仅传到手机。App 与免安装模式都会用 Android Keystore 加密保存。",
    model: "模型名称",
    fetchModels: "获取模型列表",
    advanced: "高级设置",
    full: "已填写完整的 Chat 接口地址",
    thinking: "思考模式",
    thinkingNote:
      "默认关闭。部分模型强制思考，选择支持关闭思考的模型才能加快执行。",
    save: "测试并保存到手机",
    live: "手机执行画面",
    empty: "任务执行画面会显示在这里。",
    previewNote: "接管后可直接操作视频；App 模式也可使用手机悬浮窗。",
    boundary: "关闭网页后任务会继续。支付、删除文件等高危操作仍需你确认。",
    local: "本机连接 · ADB 授权 · 无云端中转",
    close: "关闭连接网页",
    ready: "已连接",
    engine: "请先在悟空 App 中激活执行引擎。",
    overlay: "请先在手机上允许悟空显示悬浮控制条。",
    configure: "请先配置执行模型。",
    saved: "测试通过，已保存到手机。",
    testing: "正在通过手机测试…",
    loading: "正在加载…",
    noModels: "服务商没有返回模型列表，请手动填写模型名称。",
    configuredKey: "手机已有密钥，留空即可沿用",
    newKey: "请输入你的 API Key",
    approval: "请在手机上确认这次高危操作。",
    closed: "连接网页已关闭，已提交的任务继续在手机执行。",
    notConnected: "连接已断开",
    retry: "连接中断，正在查询原任务，请勿重复提交。",
    unsubmitted: "暂未找到任务回执，可使用原任务 ID 重试。",
    manual: "已暂停，可操作视频画面或手机",
    step: "第",
    unknown: "已有配置",
    phases: {
      RUNNING: "执行中",
      PREPARING: "准备中",
      PAUSED: "已接管",
      AWAITING_APPROVAL: "等待确认",
      STOPPING: "正在停止",
      COMPLETED: "已完成",
      FAILED: "失败",
      CANCELLED: "已停止",
      INTERRUPTED: "执行中断",
      IDLE: "准备就绪",
    },
  },
};
let language = navigator.language.startsWith("zh") ? "zh" : "en";
let token =
  location.hash.slice(1) || sessionStorage.getItem("wukong.session") || "";
if (token) {
  sessionStorage.setItem("wukong.session", token);
  history.replaceState(null, "", location.pathname);
}
let savedModel = null,
  presets = [],
  current = null,
  submitted = null,
  retryId = null,
  frameUrl = null,
  closed = false,
  refreshing = false;
let backendStatus = null,
  usbConnection = null,
  refreshTimer = null,
  videoStream = null,
  videoDisplay = null,
  videoStarting = false;
let taskId = sessionStorage.getItem("wukong.task");
const terminal = new Set(["COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"]);
function t(key) {
  return words[language][key] || key;
}
function translate() {
  document.documentElement.lang = language === "zh" ? "zh-CN" : "en";
  document
    .querySelectorAll("[data-i]")
    .forEach((el) => (el.textContent = t(el.dataset.i)));
  document
    .querySelectorAll("[data-placeholder]")
    .forEach((el) => (el.placeholder = t(el.dataset.placeholder)));
  $("language").textContent = language === "en" ? "中文" : "EN";
  if (current) renderTask(current);
}
$("language").onclick = () => {
  language = language === "en" ? "zh" : "en";
  translate();
};
function notice(message, error = false, id = "notice") {
  const el = $(id);
  el.textContent = message;
  el.classList.toggle("error", error);
}
async function api(path, body) {
  const response = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      Authorization: "Bearer " + token,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  if (!response.ok) {
    let message;
    try {
      message = (await response.json()).error;
    } catch {
      message = "HTTP " + response.status;
    }
    throw new Error(message);
  }
  if (response.headers.get("Content-Type").startsWith("application/json"))
    return response.json();
  return response.blob();
}
function modelBody() {
  return {
    providerId: $("provider").value,
    endpoint: $("endpoint").value.trim(),
    model: $("model").value.trim(),
    key: $("key").value,
    useFullEndpoint: $("full").checked,
    thinkingEnabled: $("thinking").checked,
  };
}
function populate(info) {
  savedModel = info;
  presets = info.presets || [];
  $("provider").replaceChildren();
  const names = {
    deepseek: "DeepSeek",
    qwen: language === "zh" ? "通义千问" : "Qwen",
    zhipu: language === "zh" ? "智谱" : "Zhipu",
    kimi: "Kimi",
    custom: language === "zh" ? "自定义" : "Custom",
    auto: t("unknown"),
  };
  const list = [...presets];
  if (info.providerId === "auto") list.push({ id: "auto" });
  for (const preset of list) {
    const option = document.createElement("option");
    option.value = preset.id;
    option.textContent = names[preset.id] || preset.id;
    $("provider").append(option);
  }
  $("provider").value = info.providerId;
  $("endpoint").value = info.endpoint;
  $("model").value = info.model;
  $("full").checked = info.useFullEndpoint;
  $("thinking").checked = info.thinkingEnabled;
  $("key").value = "";
  $("key").placeholder = info.configured ? t("configuredKey") : t("newKey");
  $("model-summary").textContent = info.configured
    ? info.model
    : t("configure");
  $("model-panel").open = !info.configured;
  platformLink();
}
function platformLink() {
  const preset = presets.find((p) => p.id === $("provider").value);
  $("platform").hidden = !preset?.platform;
  if (preset?.platform) $("platform").href = preset.platform;
}
$("provider").onchange = () => {
  const p = presets.find((p) => p.id === $("provider").value);
  if (!p) return;
  $("key").value = "";
  $("key").placeholder = t("newKey");
  $("endpoint").value = p.endpoint;
  $("model").value = p.model;
  $("models").replaceChildren();
  $("full").checked = false;
  $("thinking").checked = false;
  platformLink();
};
$("fetch-models").onclick = async () => {
  const button = $("fetch-models");
  button.disabled = true;
  notice(t("loading"), false, "model-notice");
  try {
    const body = modelBody();
    body.model = body.model || "discovery";
    const result = await api("/v1/models", body);
    $("models").replaceChildren();
    for (const name of result.models) {
      const option = document.createElement("option");
      option.value = name;
      $("models").append(option);
    }
    notice(
      result.models.length ? `${result.models.length} models` : t("noModels"),
      false,
      "model-notice",
    );
  } catch (e) {
    notice(e.message, true, "model-notice");
  } finally {
    button.disabled = false;
  }
};
$("model-form").onsubmit = async (e) => {
  e.preventDefault();
  const button = $("save-model");
  button.disabled = true;
  notice(t("testing"), false, "model-notice");
  const body = modelBody();
  try {
    await api("/v1/model/test", body);
    const result = await api("/v1/model", body);
    populate(result);
    $("model-panel").open = true;
    notice(t("saved"), false, "model-notice");
  } catch (e) {
    notice(e.message, true, "model-notice");
  } finally {
    body.key = "";
    $("key").value = "";
    button.disabled = false;
  }
};
function renderTask(value) {
  current = value;
  $("task").hidden = false;
  $("task-goal").textContent = value.goal || "";
  $("task-id").textContent = value.id;
  $("phase").textContent = words[language].phases[value.phase] || value.phase;
  $("task-message").textContent = value.result || value.message || "";
  const finished = terminal.has(value.phase);
  $("run").disabled = !finished;
  $("takeover").hidden =
    finished ||
    value.phase === "PAUSED" ||
    (value.display !== "virtual" && backendStatus?.backend !== "standalone");
  $("resume").hidden = finished || value.phase !== "PAUSED";
  $("stop").hidden = finished;
  $("logs").disabled = !finished;
  $("events").replaceChildren();
  for (const event of (value.events || []).slice(-8)) {
    const li = document.createElement("li");
    const small = document.createElement("small");
    small.textContent = `${t("step")} ${event.step} · ${words[language].phases[event.phase] || event.phase}`;
    li.append(small, document.createTextNode(event.message));
    $("events").append(li);
  }
  const standalone = backendStatus?.backend === "standalone";
  const webManual = !!videoStream;
  $("approval-controls").hidden = !(
    standalone &&
    value.phase === "AWAITING_APPROVAL" &&
    value.pendingApproval
  );
  $("approval-message").textContent = value.pendingApproval?.message || "";
  $("manual-controls").hidden = !(webManual && value.phase === "PAUSED");
  if (standalone && value.phase === "PAUSED")
    notice(
      language === "zh"
        ? "已暂停，可直接操作右侧画面；手机主屏也可以手动接管。"
        : "Paused. Interact with the preview, or use your phone.",
    );
  else if (value.phase === "AWAITING_APPROVAL")
    notice(
      standalone
        ? language === "zh"
          ? "请在这里确认本次高危操作。"
          : "Confirm this sensitive action here."
        : t("approval"),
    );
  else if (value.phase === "PAUSED") notice(t("manual"));
  else notice("");
}
$("task-form").onsubmit = async (e) => {
  e.preventDefault();
  if (!savedModel?.configured) {
    $("model-panel").open = true;
    notice(t("configure"), true);
    return;
  }
  const goal = $("goal").value.trim();
  const display = $("display").value;
  const same =
    submitted && submitted.goal === goal && submitted.display === display;
  const id = same && retryId ? retryId : crypto.randomUUID();
  submitted = { id, goal, display };
  retryId = id;
  taskId = id;
  sessionStorage.setItem("wukong.task", id);
  $("run").disabled = true;
  try {
    const value = await api("/v1/tasks", submitted);
    retryId = null;
    renderTask(value);
  } catch (error) {
    notice(error.message + " · " + id, true);
    try {
      renderTask(await api("/v1/tasks/" + id));
      retryId = null;
    } catch {
      $("run").disabled = false;
      notice(error.message + " · " + t("unsubmitted") + " " + id, true);
    }
  }
};
for (const action of ["stop", "takeover", "resume"])
  $(action).onclick = async () => {
    const button = $(action);
    button.disabled = true;
    try {
      if (action === "resume" && videoStream) await videoStream.idle();
      renderTask(await api(`/v1/tasks/${taskId}/${action}`, {}));
    } catch (error) {
      notice(error.message, true);
    } finally {
      button.disabled = false;
    }
  };
$("logs").onclick = async () => {
  try {
    const blob = await api(`/v1/tasks/${taskId}/logs`);
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `wukong-run-${taskId}.zip`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) {
    notice(error.message, true);
  }
};
$("close").onclick = async () => {
  try {
    if (videoStream) await videoStream.close();
    await api("/session/close", {});
    closed = true;
    $("connection").textContent = t("notConnected");
    $("run").disabled = true;
    notice(t("closed"));
    sessionStorage.removeItem("wukong.session");
  } catch (error) {
    notice(error.message, true);
  }
};
async function refresh() {
  if (closed || refreshing || document.hidden || backendStatus?.needsUsb)
    return;
  refreshing = true;
  try {
    if (taskId) {
      try {
        renderTask(await api("/v1/tasks/" + taskId));
      } catch (error) {
        if (!retryId) notice(error.message, true);
      }
    }
    const status = await api("/v1/status");
    backendStatus = status;
    const target =
      $("display").value === "physical" ? 0 : status.virtualDisplayId;
    if (target === 0 && $("display").value === "virtual") {
      $("preview").hidden = true;
      $("preview-empty").hidden = false;
      return;
    }
    if (
      target >= 0 &&
      (!videoStream || videoDisplay !== target) &&
      !videoStarting
    ) {
      videoStarting = true;
      try {
        if (videoStream) await videoStream.close();
        videoStream = null;
        const { startVideo } = await import("/video.js");
        videoStream = await startVideo({
          api,
          adb: usbConnection?.adb,
          displayId: target,
          canvas: $("preview"),
          fetchStream: (signal) =>
            fetch("/v1/video/stream", {
              headers: { Authorization: "Bearer " + token },
              signal,
            }).then((response) => {
              if (!response.ok) throw new Error("Video request failed");
              return response;
            }),
          canInput: () => current?.phase === "PAUSED",
          onSize: (w, h) => {
            $("preview").width = w;
            $("preview").height = h;
          },
          onError: (error) => {
            notice(error.message, true);
            const failed = videoStream;
            videoStream = null;
            videoDisplay = null;
            failed?.close().catch(() => {});
          },
        });
        videoDisplay = target;
        $("preview").hidden = false;
        $("preview-empty").hidden = true;
      } catch (error) {
        notice(error.message, true);
        $("preview").hidden = true;
        $("preview-empty").hidden = false;
      } finally {
        videoStarting = false;
      }
    }
    $("frame-status").textContent = videoStream ? "scrcpy · H.264" : "";
  } catch (error) {
    $("connection").textContent = t("notConnected");
    notice(error.message, true);
    $("run").disabled = true;
  } finally {
    refreshing = false;
  }
}
async function init() {
  translate();
  try {
    const status = await api("/v1/status");
    backendStatus = status;
    $("usb-panel").hidden = !status.needsUsb;
    if (status.needsUsb) {
      $("connection").textContent =
        language === "zh" ? "等待 USB 连接" : "Waiting for USB";
      $("usb-connect").textContent =
        language === "zh" ? "通过 USB 连接手机" : "Connect phone via USB";
      $("usb-note").textContent =
        language === "zh"
          ? "打开 USB 调试，选择手机，并在手机上允许调试。无需安装悟空 App。"
          : "Enable USB debugging, select your phone, and allow debugging on the phone. No App installation required.";
      $("run").disabled = true;
      return;
    }
    $("run").disabled = false;
    $("connection").textContent =
      t("ready") +
      " · " +
      (status.backend === "standalone" ? "app_process" : "App");
    populate(status.model);
    if (status.protocol !== 1)
      throw new Error("Unsupported companion protocol");
    if (status.locked || !status.interactive)
      notice(
        language === "zh"
          ? "请先唤醒并解锁手机。"
          : "Wake and unlock the phone first.",
        true,
      );
    else if (!status.engineReady) notice(t("engine"), true);
    else if (!status.overlayReady) notice(t("overlay"), true);
    await refresh();
    if (!refreshTimer) refreshTimer = setInterval(refresh, 1200);
  } catch (error) {
    $("connection").textContent = t("notConnected");
    notice(error.message, true);
    $("run").disabled = true;
  }
}
$("usb-connect").onclick = async () => {
  const button = $("usb-connect");
  button.disabled = true;
  try {
    const { connectUsb } = await import("/webusb.js");
    usbConnection = await connectUsb({
      api,
      expectedSerial: backendStatus?.expectedSerial,
      notify: (value) => ($("usb-status").textContent = value),
    });
    await init();
  } catch (error) {
    $("usb-status").textContent = error.message;
  } finally {
    button.disabled = false;
  }
};
for (const [button, allowed] of [
  ["approve-once", true],
  ["decline", false],
])
  $(button).onclick = async () => {
    if (!current?.pendingApproval) return;
    try {
      renderTask(
        await api(`/v1/tasks/${taskId}/approve`, {
          id: current.pendingApproval.id,
          allowed,
        }),
      );
    } catch (error) {
      notice(error.message, true);
    }
  };
let pointerStart = null;
$("preview").onpointerdown = (event) => {
  if (!$("manual-controls").hidden) {
    event.preventDefault();
    pointerStart = {
      x: event.clientX,
      y: event.clientY,
      at: performance.now(),
    };
    $("preview").setPointerCapture(event.pointerId);
  }
};
$("preview").onpointerup = async (event) => {
  if (!pointerStart) return;
  const start = pointerStart;
  pointerStart = null;
  const image = $("preview"),
    box = image.getBoundingClientRect();
  const scale = Math.min(box.width / image.width, box.height / image.height);
  const w = image.width * scale,
    h = image.height * scale,
    left = box.left + (box.width - w) / 2,
    top = box.top + (box.height - h) / 2;
  function point(x, y) {
    if (x < left || x > left + w || y < top || y > top + h) return null;
    return {
      x: Math.round(((x - left) / w) * 1000),
      y: Math.round(((y - top) / h) * 1000),
    };
  }
  const a = point(start.x, start.y),
    b = point(event.clientX, event.clientY);
  if (!a || !b) return;
  try {
    await videoStream.input({
      taskId,
      kind: "gesture",
      x: a.x,
      y: a.y,
      endX: b.x,
      endY: b.y,
      duration: Math.max(
        60,
        Math.min(2000, Math.round(performance.now() - start.at)),
      ),
    });
  } catch (error) {
    notice(error.message, true);
  }
};
for (const kind of ["back", "home"])
  $("manual-" + kind).onclick = () =>
    videoStream
      .input({ taskId, kind })
      .catch((error) => notice(error.message, true));
$("manual-send").onclick = () =>
  videoStream
    .input({ taskId, kind: "text", text: $("manual-text").value })
    .then(() => ($("manual-text").value = ""))
    .catch((error) => notice(error.message, true));
init();
