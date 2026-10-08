import { useEffect } from 'react';
import { useConnection } from './store/connection';
import { ConnectPage } from './features/connect/ConnectPage';
import { AppShell } from './features/shell/AppShell';
import { startStreamBridge } from './bridge/streamBridge';

export function App() {
  const view = useConnection((s) => s.view);
  const booting = useConnection((s) => s.booting);
  const boot = useConnection((s) => s.boot);

  useEffect(() => {
    // One subscription for the app's lifetime, before anything can stream.
    startStreamBridge();
    void boot();
  }, [boot]);

  if (booting && !view) return <div className="boot">正在启动…</div>;
  // `connected`, not `baseUrl`: a restored target without a token is an
  // address we cannot talk to yet.
  return view?.connected ? <AppShell /> : <ConnectPage />;
}
