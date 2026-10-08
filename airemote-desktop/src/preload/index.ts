import { contextBridge, ipcRenderer } from 'electron';
import { IPC } from '../shared/ipc';
import type {
  AiremoteBridge,
  AppPrefs,
  ConnectInput,
  DaemonRequest,
  NotifyInput,
  StreamCancelInput,
  StreamEvent,
  StreamSpec,
  TrayState,
} from '../shared/ipc';

/**
 * Sandboxed preload. Its only bare runtime dependency must stay `electron` —
 * one stray `require()` makes a sandboxed preload fail silently and the whole
 * bridge comes back `undefined`. tests/preload-sandbox.test.ts pins this down
 * (transitively, so relative helper modules are checked too).
 */
const api: AiremoteBridge = {
  boot: () => ipcRenderer.invoke(IPC.boot),
  request: (req: DaemonRequest) => ipcRenderer.invoke(IPC.request, req),
  connGet: () => ipcRenderer.invoke(IPC.connGet),
  connSet: (input: ConnectInput) => ipcRenderer.invoke(IPC.connSet, input),
  connClear: () => ipcRenderer.invoke(IPC.connClear),
  connProbe: () => ipcRenderer.invoke(IPC.connProbe),
  appInfo: () => ipcRenderer.invoke(IPC.appInfo),
  prefsGet: () => ipcRenderer.invoke(IPC.prefsGet),
  prefsSet: (patch: Partial<AppPrefs>) => ipcRenderer.invoke(IPC.prefsSet, patch),
  trayState: (state: TrayState) => ipcRenderer.invoke(IPC.trayState, state),
  notify: (input: NotifyInput) => ipcRenderer.invoke(IPC.notify, input),
  notifyClose: (id: string) => ipcRenderer.invoke(IPC.notifyClose, id),
  onSelectSession: (listener: (sessionId: string) => void) => {
    const handler = (_event: unknown, sessionId: string): void => listener(sessionId);
    ipcRenderer.on(IPC.selectSession, handler);
    return () => ipcRenderer.removeListener(IPC.selectSession, handler);
  },
  streamStart: (spec: StreamSpec) => ipcRenderer.invoke(IPC.streamStart, spec),
  streamCancel: (input: StreamCancelInput) => ipcRenderer.invoke(IPC.streamCancel, input),
  onStream: (listener: (event: StreamEvent) => void) => {
    const handler = (_event: unknown, payload: StreamEvent): void => listener(payload);
    ipcRenderer.on(IPC.streamEvent, handler);
    return () => ipcRenderer.removeListener(IPC.streamEvent, handler);
  },
};

contextBridge.exposeInMainWorld('airemote', api);
