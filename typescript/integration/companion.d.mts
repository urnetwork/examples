export function companionTransport(url: string, token: string, WebSocketClass?: typeof WebSocket): {
  open(callbacks: {opened(): void; message(frame: Uint8Array): void; closed(reason?: string): void}): {send(frame: Uint8Array): void; close(): void};
};
export function openMessageDevice<T>(URNetwork: {init(options?: any): Promise<{createExtensionDeviceRemote(config: any): T; close(): void}>}, config: any, token: string | undefined, wasmOptions?: any): Promise<{device: T; sdk: unknown; close(): void}>;
