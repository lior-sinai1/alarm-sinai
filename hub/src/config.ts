import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(__dirname, "..", "config.json");

export interface HubConfig {
  tuya: {
    clientId: string;
    clientSecret: string;
    deviceId: string;
    baseUrl: string;
    pollIntervalMs?: number;
    motionCode: string;
    temperatureCode?: string;
    temperatureScale?: number;
    illuminanceCode?: string;
    illuminanceScale?: number;
    humidityCode?: string;
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
        `Tuya Cloud credentials and Fibaro Home Center details.`,
    );
  }

  const config = JSON.parse(raw) as HubConfig;

  const required = [
    ["tuya.clientId", config.tuya?.clientId],
    ["tuya.clientSecret", config.tuya?.clientSecret],
    ["tuya.deviceId", config.tuya?.deviceId],
    ["tuya.baseUrl", config.tuya?.baseUrl],
    ["tuya.motionCode", config.tuya?.motionCode],
    ["fibaro.host", config.fibaro?.host],
    ["fibaro.username", config.fibaro?.username],
    ["fibaro.password", config.fibaro?.password],
    ["fibaro.globalVariableName", config.fibaro?.globalVariableName],
  ] as const;

  const missing = required.filter(([, value]) => !value).map(([key]) => key);
  if (missing.length > 0) {
    throw new Error(`config.json is missing required field(s): ${missing.join(", ")}`);
  }

  const optionalPairs = [
    ["illuminanceCode", "illuminanceVariableName"],
    ["temperatureCode", "temperatureVariableName"],
    ["humidityCode", "humidityVariableName"],
  ] as const;

  for (const [codeKey, varKey] of optionalPairs) {
    const code = config.tuya[codeKey];
    const varName = config.fibaro[varKey];
    if (code && !varName) {
      throw new Error(`config.json: tuya.${codeKey} is set but fibaro.${varKey} is missing`);
    }
    if (varName && !code) {
      throw new Error(`config.json: fibaro.${varKey} is set but tuya.${codeKey} is missing`);
    }
  }

  return config;
}
