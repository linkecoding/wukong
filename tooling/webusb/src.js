import { Adb, AdbDaemonTransport } from "@yume-chan/adb";
import { AdbDaemonWebUsbDeviceManager } from "@yume-chan/adb-daemon-webusb";
import AdbWebCredentialStore from "@yume-chan/adb-credential-web";

const encoder = new TextEncoder(),
  decoder = new TextDecoder();
const encode = (data) => {
  let result = "";
  for (let i = 0; i < data.length; i += 16384)
    result += String.fromCharCode(...data.subarray(i, i + 16384));
  return btoa(result);
};
export async function connectUsb({
  api,
  notify,
  expectedSerial,
  forceStandalone = false,
}) {
  const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
  if (!manager)
    throw new Error(
      "WebUSB requires desktop Chrome or Edge in a secure context.",
    );
  const granted = await manager.getDevices();
  const device =
    granted.length === 1 ? granted[0] : await manager.requestDevice();
  if (!device) throw new Error("No USB device selected");
  if (expectedSerial && device.serial !== expectedSerial)
    throw new Error("Wrong USB device selected");
  notify(
    "Opening USB connection; unlock the phone and allow USB debugging if prompted…",
  );
  const connection = await device.connect();
  let transport;
  try {
    transport = await AdbDaemonTransport.authenticate({
      serial: device.serial,
      connection,
      credentialStore: new AdbWebCredentialStore("Wukong WebUSB"),
    });
  } catch (error) {
    await device.raw.close();
    throw error;
  }
  const adb = new Adb(transport);
  const shell = (command) => adb.createSocketAndWait("shell:" + command);
  let stopped = false,
    session = null,
    credential = null,
    port = 39744,
    kind = "standalone";
  async function http(method, path, body, timeout = 80000) {
    const socket = await adb.createSocket("tcp:" + port);
    const reader = socket.readable.getReader(),
      writer = socket.writable.getWriter();
    const timer = setTimeout(() => socket.close(), timeout);
    try {
      const payload =
        body == null ? new Uint8Array() : encoder.encode(JSON.stringify(body));
      const headers = encoder.encode(
        `${method} ${path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${credential}\r\nContent-Type: application/json\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n`,
      );
      // Read concurrently with writes: ADB streams use bounded flow control.
      const read = (async () => {
        const chunks = [];
        let length = 0;
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          length += value.length;
          if (length > 4 * 1024 * 1024 + 16384)
            throw new Error("Response too large");
          chunks.push(value);
        }
        const data = new Uint8Array(length);
        let offset = 0;
        for (const chunk of chunks) {
          data.set(chunk, offset);
          offset += chunk.length;
        }
        return data;
      })();
      await writer.write(headers);
      if (payload.length) await writer.write(payload);
      const raw = await read;
      let boundary = -1;
      for (let i = 0; i < raw.length - 3; i++)
        if (
          raw[i] === 13 &&
          raw[i + 1] === 10 &&
          raw[i + 2] === 13 &&
          raw[i + 3] === 10
        ) {
          boundary = i;
          break;
        }
      if (boundary < 0) throw new Error("Invalid device HTTP response");
      const header = decoder.decode(raw.subarray(0, boundary));
      return {
        status: Number(header.split(" ")[1]),
        contentType:
          header.match(/Content-Type:\s*([^\r\n]+)/i)?.[1] ||
          "application/json",
        data: encode(raw.subarray(boundary + 4)),
      };
    } finally {
      clearTimeout(timer);
      reader.releaseLock();
      writer.releaseLock();
      await socket.close();
    }
  }
  try {
    // Preference is resolved only after an authorized transport can inspect the phone.
    const installed = await shell(
      "cmd package list packages -e com.wukong.phone",
    );
    if (
      !forceStandalone &&
      installed.split(/\r?\n/).includes("package:com.wukong.phone")
    ) {
      const standaloneToken = (
        await shell(
          "if [ -r /data/local/tmp/wukong-standalone/access.token ]; then cat /data/local/tmp/wukong-standalone/access.token; fi",
        )
      ).trim();
      if (/^[a-f0-9]{64}$/.test(standaloneToken)) {
        credential = standaloneToken;
        let status = null;
        try {
          const value = await http("GET", "/v1/status", null, 3000);
          if (value.status === 200) status = JSON.parse(atob(value.data));
        } catch {}
        if (status) {
          if (status.busy)
            throw new Error(
              "Stop the standalone task before switching to the App",
            );
          await http("POST", "/v1/disconnect", {});
          await new Promise((r) => setTimeout(r, 500));
        }
      }
      notify("Starting the installed Wukong app…");
      await shell(
        "am start -W -n com.wukong.phone/.companion.CompanionActivity",
      );
      const prepared = await shell(
        "content call --uri content://com.wukong.phone.bootstrap --method companion",
      );
      credential = prepared.match(/\btoken=([a-f0-9]{64})\b/)?.[1];
      if (!credential)
        throw new Error(
          "Installed Wukong needs companion support. Update it or choose standalone mode explicitly.",
        );
      port = 39743;
      kind = "app";
    } else {
      notify("Starting the on-phone runtime; no App installation…");
      const bootstrap = await api("/v1/transport/bootstrap", {
        serial: device.serial,
      });
      credential = bootstrap.token;
      const phoneCredential = (
        await shell(
          "if [ -r /data/local/tmp/wukong-standalone/access.token ]; then cat /data/local/tmp/wukong-standalone/access.token; fi",
        )
      ).trim();
      if (/^[a-f0-9]{64}$/.test(phoneCredential)) {
        credential = phoneCredential;
        await api("/v1/transport/bootstrap", {
          serial: device.serial,
          credential,
        });
      }
      const build = await api("/v1/runtime-manifest");
      let ready = false;
      try {
        const response = await http("GET", "/v1/status", null, 3000);
        if (response.status === 200) {
          const status = JSON.parse(atob(response.data));
          ready = status.buildId === build.buildId;
          if (!ready) {
            if (status.busy)
              throw new Error(
                "Stop the existing task before upgrading the runtime",
              );
            await http("POST", "/v1/disconnect", {});
            await new Promise((r) => setTimeout(r, 500));
          }
        }
      } catch (error) {
        if (error.message.includes("Stop the existing")) throw error;
      }
      if (!ready) {
        const artifact = await api("/v1/runtime.jar");
        const manifest = await api("/v1/runtime-manifest");
        const data = new Uint8Array(await artifact.arrayBuffer());
        const digest = Array.from(
          new Uint8Array(await crypto.subtle.digest("SHA-256", data)),
          (b) => b.toString(16).padStart(2, "0"),
        ).join("");
        if (digest !== manifest.sha256)
          throw new Error("Runtime checksum mismatch");
        const directory = "/data/local/tmp/wukong-standalone";
        await shell(
          `mkdir -p ${directory}; chmod 700 ${directory}; if [ -f ${directory}/runtime.jar ]; then chmod 600 ${directory}/runtime.jar; fi`,
        );
        const sync = await adb.sync();
        try {
          await sync.write({
            filename: directory + "/runtime.jar",
            permission: 0o600,
            file: byteStream(data),
          });
          await sync.write({
            filename: directory + "/activation",
            permission: 0o600,
            file: byteStream(encoder.encode(credential)),
          });
        } finally {
          await sync.dispose();
        }
        await shell(`chmod 444 ${directory}/runtime.jar`);
        const command = `CLASSPATH=${directory}/runtime.jar nohup app_process /system/bin com.wukong.phone.StandaloneMain ${directory}/activation ${directory}/tasks >${directory}/runtime.log 2>&1 </dev/null &`;
        await shell("sh -c '" + command + "'");
      }
    }
    let ready = false;
    for (let n = 0; n < 50; n++) {
      try {
        const reply = await http("GET", "/v1/status", null, 4000);
        if (reply.status === 200) {
          ready = true;
          break;
        }
      } catch {}
      await new Promise((resolve) => setTimeout(resolve, 150));
    }
    if (!ready)
      throw new Error("Phone runtime not ready. Inspect its runtime.log.");
    session = (
      await api("/v1/transport/connect", { serial: device.serial, kind })
    ).session;
    const heartbeat = setInterval(
      () => api("/v1/transport/heartbeat", { session }).catch(() => {}),
      4000,
    );
    notify("Connected · planner running on phone");
    (async () => {
      try {
        while (!stopped) {
          const request = await api("/v1/transport/poll", { session });
          if (request.idle) continue;
          let result;
          try {
            result = await http(request.method, request.path, request.body);
          } catch (error) {
            result = { error: error.message };
          }
          await api("/v1/transport/reply", {
            session,
            id: request.id,
            ...result,
          });
        }
      } catch (error) {
        if (!stopped) notify(error.message);
      } finally {
        clearInterval(heartbeat);
        stopped = true;
        await api("/v1/transport/disconnect", { session }).catch(() => {});
        await adb.close();
      }
    })();
    return {
      adb,
      serial: device.serial,
      kind,
      disconnect: async () => {
        stopped = true;
        await api("/v1/transport/disconnect", { session });
        await adb.close();
      },
    };
  } catch (error) {
    await adb.close();
    throw error;
  }
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
