import { create } from 'zustand';

/**
 * `⌘⇧E` — expand/collapse every tool group at once. Individual groups keep
 * their own override until the next global toggle, which is why the revision
 * counter exists: it is the signal for "drop your local state".
 */
interface ToolGroupState {
  all: boolean | null;
  revision: number;
  toggleAll(): void;
}

export const useToolGroups = create<ToolGroupState>((set, get) => ({
  all: null,
  revision: 0,

  toggleAll() {
    const next = !(get().all ?? false);
    set((state) => ({ all: next, revision: state.revision + 1 }));
  },
}));
