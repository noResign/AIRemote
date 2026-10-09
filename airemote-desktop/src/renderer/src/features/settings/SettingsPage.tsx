import { useEffect, useState } from 'react';
import { useConnection } from '../../store/connection';
import { useAppearance, ZOOM_MAX, ZOOM_MIN, RAIL_MIN, RAIL_MAX, type ThemePreference } from '../../store/appearance';
import { WorkspaceSection } from '../workspaces/WorkspaceSection';
import { PreferencesSection } from './PreferencesSection';
import type { AppInfo, AppPrefs } from '../../../../shared/ipc';

const THEME_LABEL: Record<ThemePreference, string> = { light: '浅色', dark: '深色', system: '跟随系统' };

const SOURCE_LABEL: Record<string, string> = {
  saved: '已保存的连接',
  managed: '本应用托管',
  default: '默认数据目录',
  systemd: 'systemd 服务',
  'port-scan': '端口探测',
};

/** 小节导航的顺序与命名沿用 `docs/local/pages/settings.md` §6.7。 */
export type SettingsSection =
  | 'connection'
  | 'daemon'
  | 'workspaces'
  | 'preferences'
  | 'notifications'
  | 'appearance'
  | 'about';

const SECTIONS: Array<{ id: SettingsSection; label: string }> = [
  { id: 'connection', label: '连接' },
  { id: 'daemon', label: '本机 daemon' },
  { id: 'workspaces', label: '工作区' },
  { id: 'preferences', label: '默认偏好' },
  { id: 'notifications', label: '通知' },
  { id: 'appearance', label: '外观' },
  { id: 'about', label: '关于' },
];

/**
 * Desktop settings is 左栏小节导航 + 右栏内容, not one long column (§6.7) —
 * the phone uses tabs; a desktop page has the height for a section rail.
 */
export function SettingsPage({
  onBack,
  section,
  onSectionChange,
}: {
  onBack(): void;
  section: SettingsSection;
  onSectionChange(next: SettingsSection): void;
}) {
  const view = useConnection((state) => state.view);
  const probe = useConnection((state) => state.probe);
  const probing = useConnection((state) => state.probing);
  const probeNow = useConnection((state) => state.probeNow);
  const disconnect = useConnection((state) => state.disconnect);

  const theme = useAppearance((state) => state.theme);
  const zoom = useAppearance((state) => state.zoom);
  const railWidth = useAppearance((state) => state.railWidth);
  const setTheme = useAppearance((state) => state.setTheme);
  const setZoom = useAppearance((state) => state.setZoom);
  const setRailWidth = useAppearance((state) => state.setRailWidth);

  const [info, setInfo] = useState<AppInfo | null>(null);
  const [prefs, setPrefs] = useState<AppPrefs | null>(null);

  useEffect(() => {
    void window.airemote.appInfo().then(setInfo);
    void window.airemote.prefsGet().then(setPrefs);
  }, []);

  async function updatePrefs(patch: Partial<AppPrefs>): Promise<void> {
    setPrefs(await window.airemote.prefsSet(patch));
  }

  const found = probe?.found ?? null;

  return (
    <div className="settings">
      <div className="settings-head">
        <button className="btn ghost" onClick={onBack} title="返回（Esc）">
          ← 返回
        </button>
        <span className="title">设置</span>
      </div>

      <div className="settings-split">
        <nav className="snav">
          {SECTIONS.map((item) => (
            <button
              key={item.id}
              className={`snav-item${item.id === section ? ' active' : ''}`}
              onClick={() => onSectionChange(item.id)}
            >
              {item.label}
            </button>
          ))}
        </nav>

        <div className="pane">
          <div className="scard">
            {section === 'connection' && (
              <section className="settings-section">
                <h3>连接</h3>
                <dl className="kv">
                  <dt>服务器</dt>
                  <dd className="mono">{view?.baseUrl ?? '未连接'}</dd>
                  <dt>名称</dt>
                  <dd>{view?.name ?? '—'}</dd>
                  <dt>Token</dt>
                  <dd>
                    {view?.hasToken ? '已持有' : '无'}
                    {view?.tokenSource && ` · 来自 ${view.tokenSource}`}
                    {view?.tokenEphemeral && ' · 仅本次会话（系统无 keyring，不会记住）'}
                  </dd>
                </dl>
                <div className="settings-actions">
                  <button className="btn" disabled={probing} onClick={() => void probeNow()}>
                    {probing ? '探测中…' : '重新探测本机 daemon'}
                  </button>
                  <button className="btn danger" onClick={() => void disconnect()}>
                    断开连接
                  </button>
                </div>
              </section>
            )}

            {section === 'daemon' && (
              <section className="settings-section">
                <h3>本机 daemon</h3>
                {found ? (
                  <>
                    <dl className="kv">
                      <dt>状态</dt>
                      <dd>● 运行中 · v{found.version}</dd>
                      <dt>地址</dt>
                      <dd className="mono">{found.listen}</dd>
                      <dt>主机</dt>
                      <dd>{found.hostname ?? '—'}</dd>
                      <dt>数据目录</dt>
                      <dd className="mono">{found.dataDir ?? '—'}</dd>
                      <dt>token 来源</dt>
                      <dd>{found.tokenSource ?? '未知'}</dd>
                      <dt>发现方式</dt>
                      <dd>{SOURCE_LABEL[found.source] ?? found.source}</dd>
                    </dl>
                    {found.source !== 'managed' && (
                      <div className="hint">
                        这个 daemon 不是本应用启动的，所以只能连接与查看，不能在这里启停或改手机访问开关。
                        {found.source === 'systemd' && ' 它由 systemd 管理，请在系统层面操作。'}
                      </div>
                    )}
                  </>
                ) : (
                  <div className="hint" style={{ marginTop: 0 }}>
                    候选表里没有找到本机 daemon（不代表它没在跑）。已试过：
                    <ul className="tried mono">
                      {(probe?.tried ?? []).map((entry) => (
                        <li key={entry}>{entry}</li>
                      ))}
                    </ul>
                  </div>
                )}
                <div className="hint">启动/停止/日志面板、手机访问开关属于 M4（本应用托管的 daemon 才可用）。</div>
              </section>
            )}

            {section === 'workspaces' && <WorkspaceSection />}

            {section === 'preferences' && <PreferencesSection />}

            {section === 'notifications' && (
              <section className="settings-section">
                <h3>通知</h3>
                <div className="field">
                  <label>桌面通知</label>
                  <label className="switch">
                    <input
                      type="checkbox"
                      checked={prefs?.desktopNotifications ?? true}
                      disabled={!prefs}
                      onChange={(event) => void updatePrefs({ desktopNotifications: event.target.checked })}
                    />
                    <span>
                      任务需要审批、或运行结束时弹系统通知（仅当你没在看那个会话时）。
                      审批已决会撤掉对应通知；通知不带「允许/拒绝」按钮，点一下进入会话。
                    </span>
                  </label>
                </div>
                <div className="field">
                  <label>关闭窗口时</label>
                  <div className="mode-picker">
                    {(
                      [
                        { value: 'tray', title: '最小化到托盘', desc: '应用继续常驻，任务照跑，通知还能弹' },
                        { value: 'quit', title: '退出应用', desc: '窗口关掉即退出（任务在 daemon 上，不受影响）' },
                        { value: 'ask', title: '每次询问', desc: '关闭时弹一次选择，可勾选记住' },
                      ] as const
                    ).map((option) => (
                      <button
                        key={option.value}
                        className={`mode-option${option.value === prefs?.closeBehavior ? ' active' : ''}`}
                        disabled={!prefs}
                        onClick={() => void updatePrefs({ closeBehavior: option.value })}
                      >
                        <b>{option.title}</b>
                        <span>{option.desc}</span>
                      </button>
                    ))}
                  </div>
                  <div className="hint">
                    「关闭窗口」不等于「退出应用」：任务在 daemon 上执行，关掉窗口不会中断它们。
                  </div>
                </div>
              </section>
            )}

            {section === 'appearance' && (
              <section className="settings-section">
                <h3>外观</h3>
                <div className="field">
                  <label>主题</label>
                  <div className="mode-picker">
                    {(['light', 'dark', 'system'] as ThemePreference[]).map((option) => (
                      <button
                        key={option}
                        className={`mode-option${option === theme ? ' active' : ''}`}
                        onClick={() => setTheme(option)}
                      >
                        <b>{THEME_LABEL[option]}</b>
                      </button>
                    ))}
                  </div>
                </div>
                <div className="field">
                  <label>界面缩放（{zoom}%，⌘+/−/0）</label>
                  <input
                    type="range"
                    min={ZOOM_MIN}
                    max={ZOOM_MAX}
                    step={5}
                    value={zoom}
                    onChange={(event) => setZoom(Number(event.target.value))}
                  />
                </div>
                <div className="field">
                  <label>左栏宽度（{railWidth}px）</label>
                  <input
                    type="range"
                    min={RAIL_MIN}
                    max={RAIL_MAX}
                    step={10}
                    value={railWidth}
                    onChange={(event) => setRailWidth(Number(event.target.value))}
                  />
                </div>
                <div className="hint">这些偏好存在本机（localStorage），不随会话走。</div>
              </section>
            )}

            {section === 'about' && (
              <section className="settings-section">
                <h3>关于</h3>
                <dl className="kv">
                  <dt>AIRemote Desktop</dt>
                  <dd>{info?.appVersion ?? '—'}</dd>
                  <dt>daemon</dt>
                  <dd>
                    v{found?.version ?? '—'}
                    <span className="hint">（来自 /api/health）</span>
                  </dd>
                  <dt>Electron</dt>
                  <dd>{info?.electron ?? '—'}</dd>
                  <dt>Chromium</dt>
                  <dd>{info?.chrome ?? '—'}</dd>
                  <dt>Node</dt>
                  <dd>{info?.node ?? '—'}</dd>
                </dl>
                <div className="hint">
                  桌面端不做客户端更新，升级请手动下载安装包重装。
                  <br />
                  退出应用时的 daemon 去留确认要等 M4——只有本应用 spawn 的 daemon 才需要问，
                  而当前只做附着，附着的 daemon 不问也不停。
                </div>
              </section>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
