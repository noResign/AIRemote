import { useEffect, useState } from 'react';
import { useConnection } from '../../store/connection';
import { isInsecure } from '../../../../shared/net';
import { parseAddress } from './address';
import type { DiscoveredDaemon, LocalTokenHint, PortConflict } from '../../../../shared/ipc';

/** Connection gate. Discovery of a same-machine daemon is the primary path. */
export function ConnectPage() {
  const view = useConnection((s) => s.view);
  const probe = useConnection((s) => s.probe);
  const probing = useConnection((s) => s.probing);
  const connecting = useConnection((s) => s.connecting);
  const error = useConnection((s) => s.error);
  const errorCode = useConnection((s) => s.errorCode);
  const probeNow = useConnection((s) => s.probeNow);
  const connect = useConnection((s) => s.connect);
  const disconnect = useConnection((s) => s.disconnect);

  const [address, setAddress] = useState('');
  const [token, setToken] = useState('');

  useEffect(() => {
    if (!probe) void probeNow();
  }, [probe, probeNow]);

  // A restored connection whose token could not be recovered still knows where
  // it was pointing — asking the user to retype the address would be pointless.
  useEffect(() => {
    if (view?.host && view.port) setAddress((current) => current || `${view.host}:${view.port}`);
  }, [view?.host, view?.port]);

  // A local daemon whose token lives in an env var can't be auto-read; hand the
  // user the address and ask for the token instead of failing generically.
  useEffect(() => {
    if (errorCode === 'token_required' && probe?.found) {
      setAddress(probe.found.listen);
    }
  }, [errorCode, probe]);

  const found = probe?.found ?? null;
  const conflict = probe?.portConflict ?? null;

  async function connectLocal(): Promise<void> {
    if (!found) return;
    const parsed = parseAddress(found.listen);
    if (!parsed) return;
    await connect({ ...parsed, name: found.hostname || '本机 daemon' });
  }

  async function connectRemote(): Promise<void> {
    const parsed = parseAddress(address);
    if (!parsed) return;
    await connect({ ...parsed, token: token.trim() || undefined });
  }

  const parsedAddress = parseAddress(address);
  const insecureWarning = parsedAddress ? isInsecure(parsedAddress.host, parsedAddress.tls) : false;

  return (
    <div className="gate">
      <div className="gate-card">
        <div className="gate-brand">
          <span className="lg">A</span>
          AIRemote
        </div>
        <div className="gate-slogan">远程驱动本机的编码 agent</div>

        <div className="section-label">本机 daemon</div>
        <LocalDaemonBlock
          probing={probing}
          found={found}
          conflict={conflict}
          tried={probe?.tried ?? []}
          tokenHint={probe?.localToken ?? null}
          connecting={connecting}
          onConnect={connectLocal}
          onChooseLocal={() => setAddress(`127.0.0.1:${4780}`)}
        />

        <div className="section-label" style={{ textAlign: 'center' }}>
          ── 或连接远程 ──
        </div>

        <div className="field">
          <label htmlFor="addr">服务器地址</label>
          <input
            id="addr"
            className="mono"
            placeholder="192.168.1.9:4780"
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            autoComplete="off"
          />
        </div>
        <div className="field">
          <label htmlFor="token">Token</label>
          <input
            id="token"
            type="password"
            className="mono"
            placeholder="粘贴 daemon 的访问 token"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            autoComplete="off"
          />
        </div>

        <button
          className="btn primary full"
          disabled={connecting || !parsedAddress}
          onClick={() => void connectRemote()}
        >
          {connecting ? '连接中…' : '连接'}
        </button>

        {insecureWarning && (
          <div className="error-text">
            ⚠️ 该地址不是本机且使用明文 HTTP——token 会在网络中明文传输，而这个 token
            等同于那台电脑上的远程 shell 权限。
          </div>
        )}
        {error && !insecureWarning && <div className="error-text">{error}</div>}
        {errorCode === 'token_required' && (
          <div className="hint">
            这个 daemon 的 token 来自环境变量或启动参数，客户端读不到，请手动粘贴。
          </div>
        )}

        <div className="hint">
          安全提示：不要把这个端口暴露到公网。远程驱动一个带 shell 权限的 agent
          等同于远程代码执行。
        </div>

        {view?.connected && (
          <button className="btn full" style={{ marginTop: 12 }} onClick={() => void disconnect()}>
            当前已连接 {view.name ?? view.baseUrl}，断开重连
          </button>
        )}
      </div>
    </div>
  );
}

interface LocalBlockProps {
  probing: boolean;
  found: DiscoveredDaemon | null;
  conflict: PortConflict | null;
  tried: string[];
  tokenHint: LocalTokenHint | null;
  connecting: boolean;
  onConnect(): void;
  onChooseLocal(): void;
}

function LocalDaemonBlock(props: LocalBlockProps) {
  const { probing, found, conflict, tried, tokenHint, connecting, onConnect, onChooseLocal } = props;

  if (probing && !found) {
    return (
      <div className="local-block">
        <div className="local-title">
          <span className="dot" /> 正在查找本机 daemon…
        </div>
      </div>
    );
  }

  if (found) {
    return (
      <div className="local-block found">
        <div className="local-title">
          <span className="dot ok" /> 本机 daemon · v{found.version}
        </div>
        <div className="local-meta mono">
          {found.listen}
          {found.workspace ? ` · ${found.workspace}` : ''}
        </div>
        <div className="local-meta">
          来源：{SOURCE_LABEL[found.source]}
          {found.source !== 'managed' && '（本应用未托管，只能连接，不能在此启停）'}
        </div>
        {tokenHint && <div className="hint">已从 {tokenHint.source} 读到 token</div>}
        <button className="btn primary full" style={{ marginTop: 10 }} disabled={connecting} onClick={onConnect}>
          {connecting ? '连接中…' : '连接'}
        </button>
      </div>
    );
  }

  return (
    <div className="local-block">
      <div className="local-title">
        <span className="dot warn" /> 没找到本机 daemon
      </div>
      <div className="local-meta">
        探测不出来只说明候选表里没有——不代表它没在跑。已试过：
      </div>
      <ul className="tried mono">
        {tried.map((t) => (
          <li key={t}>{t}</li>
        ))}
      </ul>
      {conflict && (
        <div className="local-meta" style={{ color: 'var(--warning)' }}>
          ⚠️ 端口 {conflict.port} 有响应但不是 airemote（HTTP {conflict.status ?? '—'}）。
        </div>
      )}
      <div className="choices">
        <button className="choice" onClick={onChooseLocal}>
          它跑在别的地址 → 填地址连接
        </button>
        <button className="choice" disabled title="M1 只做附着，不负责启动（M4）">
          确实没在跑 → 启动一个本机 daemon（M4）
        </button>
      </div>
    </div>
  );
}

const SOURCE_LABEL: Record<string, string> = {
  saved: '已保存的连接',
  managed: '本应用托管',
  default: '默认数据目录',
  systemd: 'systemd 服务',
  'port-scan': '端口探测',
};
