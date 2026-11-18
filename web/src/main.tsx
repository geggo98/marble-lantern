import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../design/tokens.css';
import './ui/ui.css';
import './ui/ui-extra.css';
import './ui/guides.css';
import { App } from './app/App';

const root = document.getElementById('root');
if (!root) throw new Error('index.html has no #root element');
createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
