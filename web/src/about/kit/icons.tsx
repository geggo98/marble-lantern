// The icons of the figure kit (design notes, section 6, "Other page parts"): 24 unit grid, stroke 1.8, drawn inline so that the kit
// does not depend on the id list of the shared sprite. Decorative: the button or link that holds an icon carries the name.
export type IconName = 'play' | 'pause' | 'reset' | 'step-back' | 'step-fwd' | 'check' | 'code' | 'data' | 'clock' | 'ext' | 'close' | 'chev' | 'down';

const PATHS: Record<IconName, React.JSX.Element> = {
  play: <path d="M8 5l11 7-11 7z" />,
  pause: <path d="M8 5v14M16 5v14" />,
  reset: (
    <>
      <path d="M5 6v5h5" />
      <path d="M5.6 11A7.5 7.5 0 1 1 7 17" />
    </>
  ),
  'step-back': <path d="M17 5 8 12l9 7zM6 5v14" />,
  'step-fwd': <path d="M7 5l9 7-9 7zM18 5v14" />,
  check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
  code: <path d="M9 7 4 12l5 5M15 7l5 5-5 5" />,
  data: <path d="M9 4C7 4 7 6 7 8s0 4-2 4c2 0 2 2 2 4s0 4 2 4M15 4c2 0 2 2 2 4s0 4 2 4c-2 0-2 2-2 4s0 4-2 4" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </>
  ),
  ext: <path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />,
  close: <path d="M6 6l12 12M18 6 6 18" />,
  chev: <path d="M9 6l6 6-6 6" />,
  down: <path d="M6 9l6 6 6-6" />,
};

export function Icon({ name }: { name: IconName }): React.JSX.Element {
  return (
    <svg className="ml-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      {PATHS[name]}
    </svg>
  );
}
