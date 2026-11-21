// The legend of the heat discs (Fig. 3.2 and 5.3, review m10 and m15): the sentence on its own line, then one row "low, five discs, high"
// that never wraps. CSS: heatlegend.css.
import './heatlegend.css';

const RADII = [2.2, 3.2, 4.2, 5.2, 6.2] as const;

export function HeatLegend({ label, low, high }: { label: string; low: string; high: string }): React.JSX.Element {
  return (
    <div className="ml-fig-legend ml-fig-heatlegend">
      <span className="ml-fig-heatlegend__text">{label}</span>
      <span className="ml-fig-heatlegend__ramp">
        <span>{low}</span>
        <svg viewBox="0 0 96 16" aria-hidden="true" focusable="false">
          {RADII.map((r, i) => (
            <circle key={i} cx={10 + i * 19} cy={8} r={r} className={`ml-fig-heat__v--${i + 1}`} />
          ))}
        </svg>
        <span>{high}</span>
      </span>
    </div>
  );
}
