// A glossary term in running text: a button with a dotted underline that opens a small popover with the short definition and a link
// to the glossary. One code path for every browser (the disclosure of plan section 1.3, without the native `popover` attribute, so
// that jsdom tests see the same behaviour): Escape closes it and returns the focus to the term; a press outside closes it.
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { englishToShow, glossaryWord, termEntry } from "../i18n/glossary";
import { useT } from "../../ui/texts";
import { hrefForAbout } from "../../ui/Chrome";
import { useChrome } from "../i18n/chrome";
import { InternalTag } from "./InternalTag";
// A cycle with markup.tsx (it renders TermPopover); both modules use the other only while rendering, so the import order does not matter.
import { Markup } from "./markup";

export function TermPopover({
  id,
  text,
  tail,
}: {
  id: string;
  text?: string;
  tail?: string;
}): React.JSX.Element {
  const { language } = useT();
  const c = useChrome();
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  const wrap = useRef<HTMLSpanElement>(null);
  const panelId = useId();
  const lang = language === "de" ? "de" : "en";
  const entry = termEntry(id, lang);
  const english =
    entry === undefined
      ? undefined
      : englishToShow(lang, entry.name, entry.english);

  useEffect(() => {
    if (!open) return;
    const onPress = (event: Event): void => {
      if (
        wrap.current &&
        event.target instanceof Node &&
        !wrap.current.contains(event.target)
      )
        setOpen(false);
    };
    document.addEventListener("pointerdown", onPress);
    return () => document.removeEventListener("pointerdown", onPress);
  }, [open]);

  const pop = useRef<HTMLSpanElement>(null);
  // Keep the popover inside the viewport: it hangs from the start of the term, so a term near the right edge would push it out.
  // Measure it without the shift, then move it left (or right) by the overflow. The width is capped in the css.
  useLayoutEffect(() => {
    const el = pop.current;
    if (!open || !el) return;
    const place = (): void => {
      el.style.translate = "";
      const rect = el.getBoundingClientRect();
      const edge =
        parseFloat(getComputedStyle(document.documentElement).fontSize) * 0.5; // a margin of 0.5rem
      const width = document.documentElement.clientWidth;
      const shift = Math.min(0, width - edge - rect.right);
      const back = Math.max(0, edge - (rect.left + shift));
      el.style.translate = shift + back === 0 ? "" : `${shift + back}px 0`;
    };
    place();
    window.addEventListener("resize", place);
    return () => window.removeEventListener("resize", place);
  }, [open]);

  if (!entry)
    return (
      <>
        {text ?? id}
        {tail}
      </>
    );
  return (
    <span
      ref={wrap}
      className="ml-term"
      onKeyDown={(event) => {
        if (event.key === "Escape" && open) {
          event.stopPropagation();
          setOpen(false);
          button.current?.focus();
        }
      }}
    >
      <span className="ml-term__glue">
        <button
          ref={button}
          type="button"
          className="ml-term__btn"
          aria-expanded={open}
          {...(open ? { "aria-controls": panelId } : {})}
          data-term={id}
          onClick={() => setOpen((v) => !v)}
        >
          {text ?? entry.name}
        </button>
        {tail}
      </span>
      {open && (
        <span
          ref={pop}
          id={panelId}
          className="ml-term__pop"
          role="group"
          aria-label={entry.name}
        >
          <strong>{entry.name}</strong>
          {english !== undefined && (
            <span className="ml-term__en" lang="en">
              {" "}
              ({english})
            </span>
          )}
          {entry.internal === true && (
            <InternalTag label={glossaryWord(lang, "internal")} />
          )}
          <span className="ml-term__short">
            <Markup text={entry.short} />
          </span>
          {entry.internal === true && (
            <span className="ml-term__hint">
              {glossaryWord(lang, "internal.hint")}
            </span>
          )}
          <a className="ml-about-link" href={hrefForAbout("glossary", id)}>
            {c("glossary_more")}
          </a>
        </span>
      )}
    </span>
  );
}
