// The shell of a topic page in the main bundle: loads the topic's chunk lazily, shows a loading line, and, when the chunk cannot be
// fetched (offline before the service worker has cached it), an alert with a retry button inside an error boundary.
// The topic page, its catalogs and its figures are in the chunk, not here (plan section 1.4).
import { Component, Suspense, lazy, useState } from 'react';
import type { ComponentType, ErrorInfo, LazyExoticComponent, ReactNode } from 'react';
import type { AboutTopic } from './routes';
import type { TopicPageProps } from './TopicPage';
import { Icon } from '../ui/primitives';
import { hrefForAbout } from '../ui/Chrome';
import { useT } from '../ui/texts';

type Loader = () => Promise<{ default: ComponentType<TopicPageProps> }>;

/** One dynamic import per topic. A record, not a function, so that a test can make a loader fail once. */
export const topicLoaders: Record<AboutTopic, Loader> = {
  architecture: () => import('./topics/architecture'),
  classical: () => import('./topics/classical'),
  inference: () => import('./topics/inference'),
  training: () => import('./topics/training'),
  tuning: () => import('./topics/tuning'),
  glossary: () => import('./topics/glossary'),
};

// React.lazy remembers a rejected import for good, so a retry needs a new lazy component.
const lazyTopics = new Map<AboutTopic, LazyExoticComponent<ComponentType<TopicPageProps>>>();

function lazyTopic(topic: AboutTopic): LazyExoticComponent<ComponentType<TopicPageProps>> {
  let component = lazyTopics.get(topic);
  if (!component) {
    component = lazy(() => topicLoaders[topic]());
    lazyTopics.set(topic, component);
  }
  return component;
}

export const forgetTopic = (topic: AboutTopic): boolean => lazyTopics.delete(topic);

function Failed({ topic, onRetry }: { topic: AboutTopic; onRetry: () => void }): React.JSX.Element {
  const { t } = useT();
  return (
    <main className="ml-page">
      <a className="ml-about__crumb" href={hrefForAbout()}>{`← ${t('about_learn_back')}`}</a>
      <div className="ml-alert" role="alert" data-topic={topic}>
        <Icon name="alert" />
        <div className="ml-alert__body">
          <p className="ml-alert__title">{t('about_learn_failed')}</p>
          <div className="ml-alert__actions">
            <button type="button" className="ml-btn" onClick={onRetry}>{t('about_learn_retry')}</button>
          </div>
        </div>
      </div>
    </main>
  );
}

interface BoundaryState {
  failed: boolean;
}

class TopicBoundary extends Component<{ topic: AboutTopic; onRetry: () => void; children: ReactNode }, BoundaryState> {
  override state: BoundaryState = { failed: false };

  static getDerivedStateFromError(): BoundaryState {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.warn('[about] topic failed to load or render', error, info.componentStack);
  }

  override render(): ReactNode {
    if (!this.state.failed) return this.props.children;
    return (
      <Failed
        topic={this.props.topic}
        onRetry={() => {
          forgetTopic(this.props.topic);
          this.props.onRetry();
        }}
      />
    );
  }
}

export function AboutTopic({ topic, section, onJumpSection, live }: TopicPageProps): React.JSX.Element {
  const { t } = useT();
  const [attempt, setAttempt] = useState(0);
  const Page = lazyTopic(topic);
  return (
    // A new key per topic: the boundary of the old topic must not keep its failure for the new one.
    <TopicBoundary key={`${topic}:${attempt}`} topic={topic} onRetry={() => setAttempt((v) => v + 1)}>
      <Suspense
        fallback={
          <main className="ml-page">
            <p className="ml-hint" role="status">{t('about_learn_loading')}</p>
          </main>
        }
      >
        <Page topic={topic} section={section} onJumpSection={onJumpSection} {...(live ? { live } : {})} />
      </Suspense>
    </TopicBoundary>
  );
}
