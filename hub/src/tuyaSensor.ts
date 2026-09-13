import TuyAPI from "tuyapi";
import type { HubConfig } from "./config.js";

const RECONNECT_DELAY_MS = 5000;

export type ChannelUpdate =
  | { channel: "motion"; active: boolean }
  | { channel: "illuminance"; value: number }
  | { channel: "temperature"; value: number }
  | { channel: "humidity"; value: number };

function isMotionActive(value: unknown): boolean {
  if (typeof value === "boolean") return value;
  if (typeof value === "string") return value.toLowerCase() === "pir";
  return false;
}

interface NumericChannel {
  channel: "illuminance" | "temperature" | "humidity";
  dpId: string;
  scale: number;
}

export function startMotionBridge(config: HubConfig, onUpdate: (update: ChannelUpdate) => void): void {
  const device = new TuyAPI({
    id: config.tuya.deviceId,
    key: config.tuya.localKey,
    ip: config.tuya.ip || undefined,
    version: config.tuya.version,
  });

  const numericChannels: NumericChannel[] = [
    config.tuya.illuminanceDpId
      ? { channel: "illuminance", dpId: config.tuya.illuminanceDpId, scale: config.tuya.illuminanceScale ?? 1 }
      : undefined,
    config.tuya.temperatureDpId
      ? { channel: "temperature", dpId: config.tuya.temperatureDpId, scale: config.tuya.temperatureScale ?? 0.1 }
      : undefined,
    config.tuya.humidityDpId
      ? { channel: "humidity", dpId: config.tuya.humidityDpId, scale: config.tuya.humidityScale ?? 1 }
      : undefined,
  ].filter((c): c is NumericChannel => c !== undefined);

  const lastValues = new Map<string, boolean | number>();
  let connecting = false;

  const handleDps = (payload: { dps: Record<string, unknown> }) => {
    const motionRaw = payload.dps[config.tuya.motionDpId];
    if (motionRaw !== undefined) {
      const active = isMotionActive(motionRaw);
      if (lastValues.get("motion") !== active) {
        lastValues.set("motion", active);
        onUpdate({ channel: "motion", active });
      }
    }

    for (const { channel, dpId, scale } of numericChannels) {
      const raw = payload.dps[dpId];
      if (raw === undefined) continue;
      const value = Number(raw) * scale;
      if (Number.isNaN(value)) continue;
      if (lastValues.get(channel) !== value) {
        lastValues.set(channel, value);
        onUpdate({ channel, value });
      }
    }
  };

  const connect = async () => {
    if (connecting) return;
    connecting = true;
    try {
      if (!config.tuya.ip) {
        await device.find();
      }
      await device.connect();
    } catch (err) {
      console.error(`[tuya] connect failed: ${(err as Error).message}. Retrying in ${RECONNECT_DELAY_MS}ms`);
      setTimeout(connect, RECONNECT_DELAY_MS);
    } finally {
      connecting = false;
    }
  };

  device.on("connected", () => console.log("[tuya] connected to sensor"));
  device.on("disconnected", () => {
    console.warn(`[tuya] disconnected. Retrying in ${RECONNECT_DELAY_MS}ms`);
    setTimeout(connect, RECONNECT_DELAY_MS);
  });
  device.on("error", (err) => console.error(`[tuya] error: ${err.message}`));
  device.on("data", handleDps);
  device.on("dp-refresh", handleDps);

  connect();
}
