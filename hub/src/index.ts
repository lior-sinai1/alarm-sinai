import { loadConfig, type HubConfig } from "./config.js";
import { ensureGlobalVariable, setVariable } from "./fibaro.js";
import { startMotionBridge, type ChannelUpdate } from "./tuyaSensor.js";

function variableNameFor(config: HubConfig, update: ChannelUpdate): string | undefined {
  switch (update.channel) {
    case "motion":
      return config.fibaro.globalVariableName;
    case "illuminance":
      return config.fibaro.illuminanceVariableName;
    case "temperature":
      return config.fibaro.temperatureVariableName;
    case "humidity":
      return config.fibaro.humidityVariableName;
  }
}

function valueFor(update: ChannelUpdate): string {
  if (update.channel === "motion") return update.active ? "1" : "0";
  return update.value.toFixed(1);
}

function describe(update: ChannelUpdate): string {
  if (update.channel === "motion") return `motion ${update.active ? "DETECTED" : "cleared"}`;
  return `${update.channel} = ${update.value.toFixed(1)}`;
}

async function main() {
  const config = loadConfig();

  const variableNames = [
    config.fibaro.globalVariableName,
    config.fibaro.illuminanceVariableName,
    config.fibaro.temperatureVariableName,
    config.fibaro.humidityVariableName,
  ].filter((name): name is string => Boolean(name));

  for (const name of variableNames) {
    await ensureGlobalVariable(config, name);
  }
  console.log(`[hub] Fibaro global variable(s) ready: ${variableNames.join(", ")}`);

  startMotionBridge(config, (update) => {
    const timestamp = new Date().toISOString();
    console.log(`[hub] ${timestamp} ${describe(update)}`);

    const name = variableNameFor(config, update);
    if (!name) return;
    setVariable(config, name, valueFor(update)).catch((err) => {
      console.error(`[hub] failed to update Fibaro: ${(err as Error).message}`);
    });
  });
}

main().catch((err) => {
  console.error(`[hub] fatal: ${(err as Error).message}`);
  process.exit(1);
});

process.on("SIGINT", () => process.exit(0));
process.on("SIGTERM", () => process.exit(0));
