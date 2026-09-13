import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(__dirname, "..", "config.json");

export interface HubConfig {
  tuya: {
    deviceId: string;
    localKey: string;
    ip?: string;
    version: string;
    motionDpId: string;
    illuminanceDpId?: string;
    illuminanceScale?: number;
    temperatureDpId?: string;
    temperatureScale?: number;
    humidityDpId?: string;
    humidityScale?: number;
  };
  fibaro: {
    host: string;
    username: string;
    password: string;
    globalVariableName: string;
    illuminanceVariableName?: string;
    temperatureVariableName?: string;
    humidityVariableName?: string;
  };
}

export function loadConfig(): HubConfig {
  let raw: string;
  try {
    raw = readFileSync(CONFIG_PATH, "utf-8");
  } catch {
    throw new Error(
      `Missing config.json in hub/. Copy config.example.json to config.json and fill in your ` +
        `Tuya device credentials and Fibaro Home Center details.`,
    );
  }

  const config = JSON.parse(raw) as HubConfig;

  const required = [
    ["tuya.deviceId", config.tuya?.deviceId],
    ["tuya.localKey", config.tuya?.localKey],
    ["fibaro.host", config.fibaro?.host],
    ["fibaro.username", config.fibaro?.username],
    ["fibaro.password", config.fibaro?.password],
    ["fibaro.globalVariableName", config.fibaro?.globalVariableName],
  ] as const;

  const missing = required.filter(([, value]) => !value).map(([key]) => key);
  if (missing.length > 0) {
    throw new Error(`config.json is missing required field(s): ${missing.join(", ")}`);
  }

  // Optional channels (illuminance/temperature/humidity) need both sides of the pairing:
  // a Tuya DP id to read from and a Fibaro variable name to write to.
  const optionalPairs = [
    ["illuminanceDpId", "illuminanceVariableName"],
    ["temperatureDpId", "temperatureVariableName"],
    ["humidityDpId", "humidityVariableName"],
  ] as const;

  for (const [dpKey, varKey] of optionalPairs) {
    const dpId = config.tuya[dpKey];
    const varName = config.fibaro[varKey];
    if (dpId && !varName) {
      throw new Error(`config.json: tuya.${dpKey} is set but fibaro.${varKey} is missing`);
    }
    if (varName && !dpId) {
      throw new Error(`config.json: fibaro.${varKey} is set but tuya.${dpKey} is missing`);
    }
  }

  return config;
}
