// The online mode is the shared UI of web/src; this entry only mounts it.
// The Hilla build defines __ONLINE__ as true (app/vite.config.ts), so the server engines register.
import '../../../../../web/design/tokens.css';
import '../../../../../web/src/ui/ui.css';
import '../../../../../web/src/ui/ui-extra.css';
import { App } from '../../../../../web/src/app/App';

export default function Home() {
  return <App />;
}
