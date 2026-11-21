// The small tag "internal" next to a glossary name: the name is our own coinage and not found in the literature under it.
// Not a button, so it adds no tap target; the explanation stands as visible text in the entry (glossary.internal.hint).
export function InternalTag({ label }: { label: string }): React.JSX.Element {
  return <span className="ml-tag ml-tag--internal" data-internal>{label}</span>;
}
