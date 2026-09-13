import type { HubConfig } from "./config.js";

function authHeader(config: HubConfig): string {
  const token = Buffer.from(`${config.fibaro.username}:${config.fibaro.password}`).toString("base64");
  return `Basic ${token}`;
}

function variableUrl(config: HubConfig, name: string): string {
  return `http://${config.fibaro.host}/api/globalVariables/${encodeURIComponent(name)}`;
}

/**
 * Creates the global variable on first run if it doesn't exist yet (HC returns 404 on PUT
 * for a variable that was never created), otherwise leaves the existing one untouched.
 */
export async function ensureGlobalVariable(config: HubConfig, name: string): Promise<void> {
  const probe = await fetch(variableUrl(config, name), {
    headers: { Authorization: authHeader(config) },
  });
  if (probe.status === 404) {
    const res = await fetch(`http://${config.fibaro.host}/api/globalVariables`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: authHeader(config) },
      body: JSON.stringify({ name, value: "0" }),
    });
    if (!res.ok) {
      throw new Error(`Failed to create Fibaro global variable "${name}": ${res.status} ${await res.text()}`);
    }
    return;
  }
  if (!probe.ok) {
    throw new Error(`Failed to reach Fibaro Home Center: ${probe.status} ${await probe.text()}`);
  }
}

export async function setVariable(config: HubConfig, name: string, value: string): Promise<void> {
  const res = await fetch(variableUrl(config, name), {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: authHeader(config) },
    body: JSON.stringify({ value }),
  });
  if (!res.ok) {
    throw new Error(`Fibaro update failed for "${name}": ${res.status} ${await res.text()}`);
  }
}
