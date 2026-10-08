import { useEffect, useState } from 'react';
import { useConnection } from '../../store/connection';
import { useScope, useSessions } from '../../store/sessions';
import { useChat, type NewSessionOptions } from '../../store/chat';
import { useAppearance, type ThemePreference } from '../../store/appearance';
import { SessionList } from '../sessions/SessionList';
import { ChatView } from '../chat/ChatView';
import { NewSessionDialog } from '../new-session/NewSessionDialog';
import { SettingsPage } from '../settings/SettingsPage';
import { CommandPalette } from '../palette/CommandPalette';
import { ShortcutHelp } from '../palette/ShortcutHelp';
import { isEditableTarget, isModifierless, matchShortcut } from '../../shortcuts/shortcuts';
import type { PaletteItem } from '../../commands/palette';

type Page = 'chat' | 'settings';

/**
 * Left rail + main area. Desktop shows the list and the content side by side
 * rather than pushing pages — "which session is running / waiting on me" has to
 * be visible without navigating.
 */
export function AppShell() {
  const view = useConnection((state) => state.view);
  const disconnect = useConnection((state) => state.disconnect);
  const connectionId = view?.baseUrl ?? null;
  const scope = useScope(connectionId);
  const load = useSessions((state) => state.load);

  const openSession = useChat((state) => state.openSession);
  const startNew = useChat((state) => state.startNew);
  const stopRun = useChat((state) => state.stop);
  const activeChat = useChat((state) => (state.activeKey ? state.byKey[state.activeKey] : undefined));

  const railWidth = useAppearance((state) => state.railWidth);
  const setRailWidth = useAppearance((state) => state.setRailWidth);
  const zoom = useAppearance((state) => state.zoom);
  const setZoom = useAppearance((state) => state.setZoom);
  const setTheme = useAppearance((state) => state.setTheme);

  const [workspaceId, setWorkspaceId] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [newSessionOpen, setNewSessionOpen] = useState(false);
  const [page, setPage] = useState<Page>('chat');
  const [railVisible, setRailVisible] = useState(true);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);

  useEffect(() => {
    if (!connectionId) return;
    void load(connectionId, workspaceId || undefined);
  }, [connectionId, workspaceId, load]);

  // Pick the default workspace once, then keep whatever the user chose.
  useEffect(() => {
    if (workspaceId) return;
    const fallback = scope.workspaces.find((workspace) => workspace.isDefault) ?? scope.workspaces[0];
    if (fallback) setWorkspaceId(fallback.id);
  }, [scope.workspaces, workspaceId]);

  // A brand-new session only learns its id when the daemon answers the first
  // prompt; highlight it in the rail without re-opening it (that would reload
  // history mid-stream).
  useEffect(() => {
    if (activeChat?.sessionId && activeChat.sessionId !== selectedId) setSelectedId(activeChat.sessionId);
  }, [activeChat?.sessionId, selectedId]);

  // The tray lives in main; only the renderer knows what is running.
  useEffect(() => {
    const running = scope.sessions.filter((session) => session.running);
    void window.airemote.trayState({
      runningCount: running.length,
      sessions: running.map((session) => ({ id: session.id, title: session.title ?? '未命名会话' })),
    });
  }, [scope.sessions]);

  // A tray entry or a notification click asked for a specific session.
  useEffect(
    () =>
      window.airemote.onSelectSession((sessionId) => {
        setPage('chat');
        setSelectedId(sessionId);
        if (connectionId) void openSession(connectionId, sessionId);
      }),
    [connectionId, openSession],
  );

  const selectedWorkspace = scope.workspaces.find((workspace) => workspace.id === workspaceId) ?? null;
  const runningCount = scope.sessions.filter((session) => session.running).length;
  const overlayOpen = paletteOpen || helpOpen || newSessionOpen;

  function select(id: string): void {
    setPage('chat');
    setSelectedId(id);
    if (connectionId) void openSession(connectionId, id);
  }

  function switchWorkspace(id: string): void {
    setWorkspaceId(id);
    setSelectedId(null);
    useChat.getState().leave();
  }

  function stepSession(delta: number): void {
    if (scope.sessions.length === 0) return;
    const index = scope.sessions.findIndex((session) => session.id === selectedId);
    const next = scope.sessions[(index + delta + scope.sessions.length) % scope.sessions.length];
    if (next) select(next.id);
  }

  function createSession(options: NewSessionOptions): void {
    if (!connectionId) return;
    setNewSessionOpen(false);
    setPage('chat');
    setSelectedId(null);
    startNew(connectionId, { ...options, workspaceId: workspaceId || null });
  }

  function refresh(): void {
    if (connectionId) void load(connectionId, workspaceId || undefined);
  }

  /**
   * Dispatch table keyed by the stable shortcut ids in `shortcuts.ts`. Adding a
   * binding means one row there plus one case here.
   */
  const actions: Record<string, () => void> = {
    'command-palette': () => setPaletteOpen((open) => !open),
    'new-session': () => setNewSessionOpen(true),
    'toggle-rail': () => setRailVisible((visible) => !visible),
    'go-sessions': () => setPage('chat'),
    'go-settings': () => setPage('settings'),
    refresh,
    'stop-run': stopRun,
    'next-session': () => stepSession(1),
    'prev-session': () => stepSession(-1),
    'zoom-in': () => setZoom(zoom + 10),
    'zoom-out': () => setZoom(zoom - 10),
    'zoom-reset': () => setZoom(100),
    help: () => setHelpOpen(true),
  };

  useEffect(() => {
    const handler = (event: KeyboardEvent): void => {
      const shortcut = matchShortcut(event);
      if (!shortcut) return;
      // A bare key must never be swallowed while the user is typing.
      if (isModifierless(shortcut.combo) && isEditableTarget(event.target)) return;
      // With an overlay up, only the palette toggle still applies (to close it).
      if (overlayOpen && shortcut.id !== 'command-palette') return;
      const action = actions[shortcut.id];
      if (!action) return;
      event.preventDefault();
      action();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  });

  function buildPaletteItems(): PaletteItem[] {
    const items: PaletteItem[] = [];
    for (const session of scope.sessions) {
      items.push({
        id: `session:${session.id}`,
        section: '会话',
        label: session.title ?? '未命名会话',
        hint: [session.runtime, session.running ? '运行中' : null].filter(Boolean).join(' · '),
        keywords: session.cwd,
        run: () => select(session.id),
      });
    }
    for (const workspace of scope.workspaces) {
      items.push({
        id: `workspace:${workspace.id}`,
        section: '工作区',
        label: workspace.name,
        hint: `${workspace.sessionCount} 个会话`,
        keywords: 'workspace',
        run: () => switchWorkspace(workspace.id),
      });
    }
    const theme = (value: ThemePreference, label: string): PaletteItem => ({
      id: `theme:${value}`,
      section: '动作',
      label,
      keywords: 'theme appearance',
      run: () => setTheme(value),
    });
    items.push(
      { id: 'action:new-session', section: '动作', label: '新建会话', keywords: 'new create', run: () => setNewSessionOpen(true) },
      { id: 'action:settings', section: '动作', label: '打开设置', keywords: 'preferences', run: () => setPage('settings') },
      { id: 'action:refresh', section: '动作', label: '刷新会话列表', keywords: 'reload refresh', run: refresh },
      { id: 'action:stop', section: '动作', label: '停止当前运行', keywords: 'cancel abort', run: stopRun },
      theme('light', '主题：浅色'),
      theme('dark', '主题：深色'),
      theme('system', '主题：跟随系统'),
      { id: 'action:help', section: '动作', label: '快捷键帮助', keywords: 'help keys', run: () => setHelpOpen(true) },
      { id: 'action:disconnect', section: '动作', label: '断开连接', keywords: 'disconnect logout', run: () => void disconnect() },
    );
    return items;
  }

  function startResize(event: React.MouseEvent): void {
    event.preventDefault();
    const onMove = (move: MouseEvent): void => setRailWidth(move.clientX);
    const onUp = (): void => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }

  return (
    <div className="shell" style={{ '--rail-w': `${railWidth}px` } as React.CSSProperties}>
      {railVisible && (
        <aside className="rail">
          <div className="rail-head">
            <div className="conn-pill">
              <span className="dot ok" />
              <span className="conn-name">{view?.name ?? view?.baseUrl}</span>
              {view?.tokenSource && (
                <span className="badge" title={`token 来源：${view.tokenSource}`}>
                  token
                </span>
              )}
            </div>
            <select className="ws-select" value={workspaceId} onChange={(event) => switchWorkspace(event.target.value)}>
              {scope.workspaces.map((workspace) => (
                <option key={workspace.id} value={workspace.id}>
                  {workspace.name} · {workspace.sessionCount} 个会话
                </option>
              ))}
            </select>
            <button className="btn" onClick={() => setNewSessionOpen(true)} title="新建会话 ⌘N">
              ＋ 新建会话 <kbd>⌘N</kbd>
            </button>
          </div>

          <div className="rail-list">
            {scope.loading && scope.sessions.length === 0 ? (
              <div className="empty">正在加载…</div>
            ) : scope.error ? (
              <div className="empty" style={{ color: 'var(--error)' }}>
                {scope.error}
              </div>
            ) : (
              <SessionList sessions={scope.sessions} selectedId={selectedId} onSelect={select} />
            )}
          </div>

          <div className="rail-foot">
            <button className="link-btn" disabled title="文件与 Diff 属于 M3">
              📁 文件
            </button>
            <button className={`link-btn${page === 'settings' ? ' active' : ''}`} onClick={() => setPage('settings')}>
              ⚙ 设置
            </button>
            <button className="link-btn" title="命令面板 ⌘K" onClick={() => setPaletteOpen(true)}>
              ⌘K
            </button>
            <span className="rail-running">{runningCount > 0 ? `● ${runningCount} 个任务运行中` : ''}</span>
          </div>
          <div className="rail-resize" onMouseDown={startResize} title="拖拽调整宽度" />
        </aside>
      )}

      <main className="main">
        {page === 'settings' ? <SettingsPage /> : <ChatView workspaceName={selectedWorkspace?.name ?? null} />}
      </main>

      {newSessionOpen && (
        <NewSessionDialog
          workspaceName={selectedWorkspace?.name ?? null}
          workspaceId={workspaceId || null}
          onCancel={() => setNewSessionOpen(false)}
          onCreate={createSession}
        />
      )}

      {paletteOpen && <CommandPalette items={buildPaletteItems()} onClose={() => setPaletteOpen(false)} />}
      {helpOpen && <ShortcutHelp onClose={() => setHelpOpen(false)} />}
    </div>
  );
}
