export interface MessageDevice {
  getRemoteConnected(): boolean;
  getSyncError(): string;
  getClientId(): string;
  getNetworkPeers(): unknown;
  addRemoteChangeListener(callback: (connected: boolean) => void): () => void;
  addNetworkPeersChangeListener(callback: (peers: any) => void): () => void;
  enableSubprotocol(id: number, callback: (message: {sourceClientId: string; bytes: Uint8Array}) => void | Promise<void>): Promise<{
    send(clientId: string, bytes: Uint8Array): Promise<boolean>;
    querySubprotocols(clientId: string, timeoutMillis?: number): Promise<number[] | null>;
    close(): Promise<void>;
    readonly closed: Promise<void>;
  }>;
}
export interface MessageCodec {
  SUBPROTOCOL: number;
  encode(kind: number, id: bigint, text?: string): Uint8Array;
  decode(input: Uint8Array): {kind: number; id: bigint; text: string};
}
export function validateCommand(args: string[]): void;
export function runMessages(device: MessageDevice, codec: MessageCodec, args: string[], options?: {
  log?: (message: string) => void;
  error?: (message: string) => void;
  timeoutMillis?: number;
  signal?: AbortSignal;
}): Promise<void>;
