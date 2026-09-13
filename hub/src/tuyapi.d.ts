declare module "tuyapi" {
  export interface TuyAPIOptions {
    id: string;
    key: string;
    ip?: string;
    version?: string;
    issueGetOnConnect?: boolean;
  }

  export interface DpsPayload {
    dps: Record<string, unknown>;
  }

  export default class TuyAPI {
    constructor(options: TuyAPIOptions);
    find(options?: { timeout?: number }): Promise<boolean>;
    connect(): Promise<boolean>;
    disconnect(): void;
    get(options?: { schema?: boolean }): Promise<unknown>;
    on(event: "connected" | "disconnected", listener: () => void): this;
    on(event: "error", listener: (error: Error) => void): this;
    on(event: "data" | "dp-refresh", listener: (payload: DpsPayload) => void): this;
  }
}
