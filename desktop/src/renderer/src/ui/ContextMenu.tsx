import { useCallback, useEffect, useState } from 'react';

export interface MenuItem {
  id: string;
  label: string;
  danger?: boolean;
  disabled?: boolean;
  run(): void;
}

export interface MenuState {
  x: number;
  y: number;
  items: MenuItem[];
}

/**
 * Right-click menus are a first-class desktop channel with no mobile
 * equivalent (docs/local/desktop_ui_design.md §7.2) — they carry the actions
 * that a phone would put behind a long-press.
 */
export function useContextMenu() {
  const [menu, setMenu] = useState<MenuState | null>(null);

  const open = useCallback((event: React.MouseEvent, items: MenuItem[]) => {
    event.preventDefault();
    event.stopPropagation();
    setMenu({ x: event.clientX, y: event.clientY, items });
  }, []);

  const close = useCallback(() => setMenu(null), []);

  return { menu, open, close };
}

export function ContextMenu({ menu, onClose }: { menu: MenuState | null; onClose(): void }) {
  useEffect(() => {
    if (!menu) return;
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') onClose();
    };
    const onDown = (): void => onClose();
    window.addEventListener('keydown', onKey);
    window.addEventListener('mousedown', onDown);
    window.addEventListener('blur', onDown);
    window.addEventListener('resize', onDown);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('mousedown', onDown);
      window.removeEventListener('blur', onDown);
      window.removeEventListener('resize', onDown);
    };
  }, [menu, onClose]);

  if (!menu) return null;

  // Keep the menu on screen when it is opened near an edge.
  const width = 190;
  const height = menu.items.length * 30 + 12;
  const left = Math.min(menu.x, window.innerWidth - width - 8);
  const top = Math.min(menu.y, window.innerHeight - height - 8);

  return (
    <div className="ctx-menu" style={{ left, top, width }} onMouseDown={(event) => event.stopPropagation()}>
      {menu.items.map((item) => (
        <button
          key={item.id}
          className={`ctx-item${item.danger ? ' danger' : ''}`}
          disabled={item.disabled}
          onClick={() => {
            onClose();
            item.run();
          }}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
