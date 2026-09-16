import type { HubConfig } from "./config.js";
import { TuyaCloudClient } from "./tuyaCloud.js";

const DEFAULT_POLL_INTERVAL_MS = 5000;

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
  code: string;
  scale: number;
}

export function startMotionBridge(config: HubConfig, onUpdate: (update: ChannelUpdate) => void): void {
  const client = new TuyaCloudClient(config);
  const pollInterval = config.tuya.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;

  const numericChannels: NumericChannel[] = [
    config.tuya.illuminanceCode
      ? { channel: "illuminance", code: config.tuya.illuminanceCode, scale: config.tuya.illuminanceScale ?? 1 }
      : undefined,
    config.tuya.temperatureCode
      ? { channel: "temperature", code: config.tuya.temperatureCode, scale: config.tuya.temperatureScale ?? 0.1 }
      : undefined,
    config.tuya.humidityCode
      ? { channel: "humidity", code: config.tuya.humidityCode, scale: config.tuya.humidityScale ?? 1 }
      : undefined,
  ].filter((c): c is NumericChannel => c !== undefined);

  const lastValues = new Map<string, boolean | number>();
  let connected = false;

  const poll = async () => {
    try {
      const status = await client.getDeviceStatus();

      if (!connected) {
        connected = true;
        console.log("[tuya] connected to cloud, polling device status");
      }

      const motionRaw = status[config.tuya.motionCode];
      if (motionRaw !== undefined) {
        const active = isMotionActive(motionRaw);
        if (lastValues.get("motion") !== active) {
          lastValues.set("motion", active);
          onUpdate({ channel: "motion", active });
        }
      }

      for (const { channel, code, scale } of numericChannels) {
        const raw = status[code];
        if (raw === undefined) continue;
        const value = Number(raw) * scale;
        if (Number.isNaN(value)) continue;
        if (lastValues.get(channel) !== value) {
          lastValues.set(channel, value);
          onUpdate({ channel, value });
        }
      }
    } catch (err) {
      if (connected) {
        connected = false;
        console.warn(`[tuya] lost connection: ${(err as Error).message}`);
      } else {
        console.error(`[tuya] poll failed: ${(err as Error).message}`);
      }
    }
  };

  poll();
  setInterval(poll, pollInterval);
}
