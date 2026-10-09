import { useState } from 'react';
import { useAppearance } from '../../store/appearance';
import { FileBrowser, type FileMode, type FileSelection } from './FileBrowser';
import { FilePane } from './FilePane';

/**
 * 右栏面板 (`desktop_ui_design.md` §2 / §6.8): the changed-files browser beside
 * the conversation, so you can watch what the agent touches without leaving the
 * chat. Selecting a file pushes the viewer in place — a back arrow returns to
 * the list — because 320px is not enough for a list *and* a viewer.
 *
 * The design also lists 待办 and 已授权工具 here; both already surface in the
 * chat itself (todo panel, session-permissions dialog), so the panel starts with
 * the files half (`§6.8`: 「右栏面板先做改动文件」).
 */
export function ChatAside({ workspaceId, onCollapse }: { workspaceId: string | null; onCollapse(): void }) {
  const width = useAppearance((state) => state.asideWidth);
  const setWidth = useAppearance((state) => state.setAsideWidth);
  const chatVisible = useAppearance((state) => state.chatVisible);
  const [mode, setMode] = useState<FileMode>('changes');
  const [root, setRoot] = useState<string | null>(null);
  const [selection, setSelection] = useState<FileSelection | null>(null);

  /** Drag the left edge; the panel is anchored right, so width is the remainder. */
  function startResize(event: React.MouseEvent): void {
    event.preventDefault();
    const onMove = (move: MouseEvent): void => setWidth(window.innerWidth - move.clientX);
    const onUp = (): void => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }

  return (
    // With the conversation collapsed there is nothing to give width back to, so
    // the panel takes the whole remainder instead of its pinned width.
    <aside className={`chat-aside${chatVisible ? '' : ' fill'}`} style={chatVisible ? { width } : undefined}>
      {chatVisible && <div className="aside-resize" onMouseDown={startResize} title="拖拽调整宽度" />}
      <div className="aside-head">
        {selection ? (
          <>
            <button className="btn ghost tiny" onClick={() => setSelection(null)} title="回到列表">
              ← 列表
            </button>
            <span className="mono aside-path" title={selection.path}>
              {selection.path}
            </span>
          </>
        ) : (
          <span className="title">文件</span>
        )}
        <button className="btn ghost tiny aside-close" onClick={onCollapse} title="收起右栏">
          ✕
        </button>
      </div>

      <div className="aside-body">
        {selection ? (
          // Always unified here: the panel is far narrower than the split minimum.
          <FilePane workspaceId={workspaceId} root={root} selection={selection} view="unified" emptyText="" />
        ) : (
          <FileBrowser
            workspaceId={workspaceId}
            root={root}
            onRootChange={setRoot}
            mode={mode}
            onModeChange={setMode}
            selectedPath={null}
            onSelect={setSelection}
          />
        )}
      </div>
    </aside>
  );
}
