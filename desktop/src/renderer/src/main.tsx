import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { initAppearance } from './store/appearance';
import './ui/tokens.css';
import './ui/app.css';

const container = document.getElementById('root');
if (!container) throw new Error('#root missing from index.html');

// Applied before the first render so the theme never flashes.
initAppearance();

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
