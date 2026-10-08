import { useState } from 'react';
import type { TodoItem } from '../../store/chat/types';

const GLYPH: Record<string, string> = {
  completed: '✓',
  in_progress: '◐',
  pending: '○',
};

/** The agent's own task list (TodoWrite), kept out of the transcript. */
export function TodoPanel({ todos }: { todos: TodoItem[] }) {
  const [open, setOpen] = useState(true);
  const done = todos.filter((todo) => todo.status === 'completed').length;
  // A fully finished list is historical noise: it would sit above every later
  // answer as a permanent strip saying "done". Only show work still in flight.
  if (todos.length === 0 || done === todos.length) return null;

  return (
    <div className="todo-panel">
      <button className="fold-head" onClick={() => setOpen((value) => !value)}>
        <span className="chevron">{open ? '▾' : '▸'}</span>
        <span>待办</span>
        <span className="badge">
          {done}/{todos.length}
        </span>
      </button>
      {open && (
        <ul className="todo-list">
          {todos.map((todo, index) => (
            <li key={`${index}-${todo.content}`} className={`todo-item ${todo.status}`}>
              <span className="todo-glyph">{GLYPH[todo.status] ?? '○'}</span>
              <span>{todo.content}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
