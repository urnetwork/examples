export function clientConfig<T extends object>(environment: Record<string, string | undefined>, config: T): T & {byJwt: string; instanceId: string};
export function openDevice<T>(URNetwork: {init(options?: any): Promise<{createPlatformDeviceRemote(config: any): T; close(): void}>}, config: any, wasmOptions?: any): Promise<{device: T; sdk: unknown; close(): void}>;
