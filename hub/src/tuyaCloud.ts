import crypto from "node:crypto";
import type { HubConfig } from "./config.js";

function sha256Hex(input: string): string {
  return crypto.createHash("sha256").update(input, "utf8").digest("hex");
}

function hmacSha256Hex(key: string, input: string): string {
  return crypto.createHmac("sha256", key).update(input, "utf8").digest("hex").toUpperCase();
}

export class TuyaCloudClient {
  private token: string | null = null;
  private tokenExpiresAt = 0;

  constructor(private config: HubConfig) {}

  private sign(method: string, path: string, accessToken = ""): { t: string; signature: string } {
    const t = Date.now().toString();
    const contentHash = sha256Hex("");
    const stringToSign = [method, contentHash, "", path].join("\n");
    const str = this.config.tuya.clientId + accessToken + t + stringToSign;
    return { t, signature: hmacSha256Hex(this.config.tuya.clientSecret, str) };
  }

  private async fetchToken(): Promise<void> {
    const path = "/v1.0/token?grant_type=1";
    const { t, signature } = this.sign("GET", path);
    const res = await fetch(this.config.tuya.baseUrl + path, {
      headers: {
        client_id: this.config.tuya.clientId,
        sign: signature,
        t,
        sign_method: "HMAC-SHA256",
      },
    });
    const json = (await res.json()) as {
      success: boolean;
      msg?: string;
      result?: { access_token: string; expire_time: number };
    };
    if (!json.success || !json.result) {
      throw new Error(`Tuya token fetch failed: ${json.msg ?? JSON.stringify(json)}`);
    }
    this.token = json.result.access_token;
    // Refresh a bit early to avoid using an expired token mid-request.
    this.tokenExpiresAt = Date.now() + (json.result.expire_time - 60) * 1000;
  }

  private async getAccessToken(): Promise<string> {
    if (!this.token || Date.now() >= this.tokenExpiresAt) {
      await this.fetchToken();
    }
    return this.token!;
  }

  async getDeviceStatus(): Promise<Record<string, unknown>> {
    const accessToken = await this.getAccessToken();
    const path = `/v1.0/devices/${this.config.tuya.deviceId}/status`;
    const { t, signature } = this.sign("GET", path, accessToken);
    const res = await fetch(this.config.tuya.baseUrl + path, {
      headers: {
        client_id: this.config.tuya.clientId,
        access_token: accessToken,
        sign: signature,
        t,
        sign_method: "HMAC-SHA256",
      },
    });
    const json = (await res.json()) as {
      success: boolean;
      msg?: string;
      result?: Array<{ code: string; value: unknown }>;
    };
    if (!json.success || !json.result) {
      throw new Error(`Tuya status fetch failed: ${json.msg ?? JSON.stringify(json)}`);
    }
    const status: Record<string, unknown> = {};
    for (const { code, value } of json.result) status[code] = value;
    return status;
  }
}
