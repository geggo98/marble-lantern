import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../design/tokens.css';
import './ui/ui.css';
import './ui/ui-extra.css';
import { App } from './app/App';
import type { BenchRunner } from './ui/Screens';

// The benchmark code loads on the first start of the benchmark, not with the app.
const benchRunner: BenchRunner = {
  run: async (onProgress, signal) => (await import('./bench/browser-runner')).createBrowserBenchRunner().run(onProgress, signal),
};

const root = document.getElementById('root');
if (!root) throw new Error('index.html has no #root element');
createRoot(root).render(
  <StrictMode>
    <App benchRunner={benchRunner} />
  </StrictMode>,
);
