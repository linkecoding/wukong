import { WritableStream } from "@yume-chan/stream-extra";
import { AdbScrcpyClient, AdbScrcpyOptions3_3_3 } from "@yume-chan/adb-scrcpy";
import { ScrcpyOptions3_3_3, ScrcpyPointerId } from "@yume-chan/scrcpy";
import {
  WebCodecsVideoDecoder,
  BitmapVideoFrameRenderer,
} from "@yume-chan/scrcpy-decoder-webcodecs";

export async function startVideo({
  api,
  adb,
  fetchStream,
  displayId,
  canvas,
  canInput,
  onSize,
  onError,
}) {
  if (!WebCodecsVideoDecoder.isSupported)
    throw new Error(
      "This browser does not support WebCodecs H.264 decoding. Use current desktop Chrome or Edge.",
    );
  /** @type {import("@yume-chan/scrcpy").ScrcpyOptions3_3_3.Init} */
  const init = {
    audio: false,
    control: true,
    videoCodec: "h264",
    videoBitRate: 4000000,
    maxFps: 30,
    maxSize: 1600,
    displayId,
    clipboardAutosync: false,
    powerOn: false,
    stayAwake: false,
    sendDeviceMeta: false,
    tunnelForward: true,
  };
  let client = null,
    stopFetch = null,
    options,
    stream,
    metadata;
  if (adb) {
    const jar = await api("/v1/scrcpy.jar");
    const manifest = await api("/v1/scrcpy-manifest");
    const data = new Uint8Array(await jar.arrayBuffer());
    const hash = Array.from(
      new Uint8Array(await crypto.subtle.digest("SHA-256", data)),
      (b) => b.toString(16).padStart(2, "0"),
    ).join("");
    if (hash !== manifest.sha256) throw new Error("scrcpy checksum mismatch");
    const path = "/data/local/tmp/wukong-scrcpy-" + manifest.version + ".jar";
    await AdbScrcpyClient.pushServer(adb, byteStream(data), path);
    options = new AdbScrcpyOptions3_3_3(init);
    client = await AdbScrcpyClient.start(adb, path, options);
    client.output.pipeTo(new WritableStream({ write() {} })).catch(() => {});
    const video = await client.videoStream;
    stream = video.stream;
    metadata = video.metadata;
  } else {
    await api("/v1/video/start", { displayId });
    const controller = new AbortController();
    stopFetch = () => controller.abort();
    const response = await fetchStream(controller.signal);
    options = new ScrcpyOptions3_3_3(init);
    const video = await options.parseVideoStreamMetadata(response.body);
    metadata = video.metadata;
    stream = video.stream.pipeThrough(options.createMediaStreamTransformer());
  }
  const decoder = new WebCodecsVideoDecoder({
    codec: metadata.codec,
    renderer: new BitmapVideoFrameRenderer(canvas),
    hardwareAcceleration: "prefer-hardware",
  });
  let width = metadata.width,
    height = metadata.height,
    closed = false,
    manual = Promise.resolve();
  decoder.sizeChanged(({ width: w, height: h }) => {
    width = w;
    height = h;
    onSize(w, h);
  });
  onSize(width, height);
  stream
    .pipeTo(decoder.writable)
    .then(() => {
      if (!closed) onError(new Error("scrcpy stream ended"));
    })
    .catch((error) => {
      if (!closed) onError(error);
    });
  async function sendInput(body) {
    if (closed || !canInput()) throw new Error("Take over the task first");
    body = { ...body, width, height };
    if (body.kind === "home" && displayId > 0)
      return api(`/v1/tasks/${body.taskId}/input`, { kind: "home" });
    if (!client) return api("/v1/video/input", body);
    const control = client.controller;
    if (body.kind === "home" || body.kind === "back") {
      const keyCode = body.kind === "home" ? 3 : 4;
      await control.injectKeyCode({
        action: 0,
        keyCode,
        repeat: 0,
        metaState: 0,
      });
      await control.injectKeyCode({
        action: 1,
        keyCode,
        repeat: 0,
        metaState: 0,
      });
      return;
    }
    if (body.kind === "text") return control.injectText(body.text);
    const touch = (action, x, y) =>
      control.injectTouch({
        action,
        pointerId: ScrcpyPointerId.Finger,
        pointerX: Math.round((x * (width - 1)) / 1000),
        pointerY: Math.round((y * (height - 1)) / 1000),
        videoWidth: width,
        videoHeight: height,
        pressure: action === 1 ? 0 : 1,
        actionButton: 0,
        buttons: 0,
      });
    await touch(0, body.x, body.y);
    const duration = Math.max(60, Math.min(2000, body.duration || 60)),
      steps = Math.max(1, Math.round(duration / 25));
    try {
      for (let n = 1; n <= steps; n++) {
        if (closed || !canInput()) throw new Error("Manual control ended");
        await new Promise((r) => setTimeout(r, duration / steps));
        await touch(
          2,
          body.x + ((body.endX - body.x) * n) / steps,
          body.y + ((body.endY - body.y) * n) / steps,
        );
      }
      await touch(1, body.endX, body.endY);
    } catch (error) {
      await touch(3, body.endX, body.endY).catch(() => {});
      throw error;
    }
  }
  return {
    decoder,
    get width() {
      return width;
    },
    get height() {
      return height;
    },
    input(body) {
      manual = manual.catch(() => {}).then(() => sendInput(body));
      return manual;
    },
    async close() {
      closed = true;
      stopFetch?.();
      decoder.dispose();
      if (client) await client.close();
      else await api("/v1/video/stop", {}).catch(() => {});
    },
    async idle() {
      await manual.catch(() => {});
    },
  };
}

// Tango uses extended stream typings; browsers supply the same native stream at runtime.
/** @param {Uint8Array} data */
function byteStream(data) {
  return /** @type {import('@yume-chan/stream-extra').ReadableStream<Uint8Array>} */ (
    /** @type {unknown} */ (
      new globalThis.ReadableStream({
        start(c) {
          c.enqueue(data);
          c.close();
        },
      })
    )
  );
}
